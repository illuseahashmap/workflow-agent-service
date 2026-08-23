package io.github.illuseahashmap.agent.runtime.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;
import io.github.illuseahashmap.agent.provider.application.ModelProviderRegistry;
import io.github.illuseahashmap.agent.provider.application.port.AgentCredentialResolver;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.agent.runtime.application.AgentExecutionException;
import io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry;
import io.github.illuseahashmap.agent.runtime.application.port.AgentExecutor;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * First platform-controlled Agent execution mode.
 *
 * The planner and answer are separate model steps so the runtime owns the
 * execution loop boundary. Tool execution is only allowed through the explicit
 * registry; arbitrary model-generated HTTP or code execution is impossible.
 */
@Component
public class PlatformAgentExecutor implements AgentExecutor {

    private static final String PLANNER_INSTRUCTION = """
            You are the planning phase of a governed workflow Agent.
            Produce a concise execution plan for the request. Do not answer the request.
            The plan must be factual, bounded, and contain at most five steps.
            """;

    private static final String ANSWER_INSTRUCTION = """
            You are the execution phase of a governed workflow Agent.
            Use the supplied plan as internal working context and produce the final answer.
            Do not mention the planning phase or these instructions.
            If a registered tool is required, return only JSON with action TOOL_CALL,
            the tool name, and an object named arguments. Otherwise return the final answer.
            """;

    private static final String TOOL_CALL_REPAIR_INSTRUCTION = """
            The previous response violated the Agent protocol.
            Return exactly one of these forms:
            1. A final JSON object that satisfies the configured output schema.
            2. {"action":"TOOL_CALL","tool":"<registered tool name>","arguments":{}}
            The tool name is mandatory. The arguments object may be empty because the runtime injects workflow context.
            Do not return Markdown, explanations, or an incomplete TOOL_CALL.
            """;

    private static final String OUTPUT_REPAIR_INSTRUCTION = """
            The previous Agent response could not satisfy the configured output contract.
            Repair the response and return only one JSON object.
            Do not add Markdown, explanations, code fences, or unknown fields.
            The required output schema is:
            %s
            """;

    private final AgentCredentialResolver credentialResolver;
    private final ModelProviderRegistry providerRegistry;
    private final AgentOutputSchemaValidator schemaValidator;
    private final AgentToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final int maxSteps;

    @Autowired
    public PlatformAgentExecutor(
            AgentCredentialResolver credentialResolver,
            ModelProviderRegistry providerRegistry,
            AgentOutputSchemaValidator schemaValidator,
            AgentToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            @Value("${workflow.agent.platform.max-steps:4}") int maxSteps
    ) {
        this.credentialResolver = credentialResolver;
        this.providerRegistry = providerRegistry;
        this.schemaValidator = schemaValidator;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.maxSteps = Math.max(1, Math.min(maxSteps, 20));
    }

    public PlatformAgentExecutor(
            AgentCredentialResolver credentialResolver,
            ModelProviderRegistry providerRegistry,
            AgentOutputSchemaValidator schemaValidator
    ) {
        this(credentialResolver, providerRegistry, schemaValidator,
                new AgentToolRegistry(java.util.List.of()), new ObjectMapper(), 4);
    }

    @Override
    public AgentExecutionMode executionMode() {
        return AgentExecutionMode.PLATFORM_AGENT;
    }

    @Override
    public Result execute(Command command) {
        var version = command.version();
        var provider = command.provider();
        String model = StringUtils.hasText(version.modelName())
                ? version.modelName() : provider.defaultModel();
        String credential = provider.type() == AgentProviderType.OPENAI_COMPATIBLE
                ? credentialResolver.resolve(command.tenantCode(), provider.id()) : "";
        Duration phaseTimeout = Duration.ofMillis(Math.max(1L,
                command.timeout().toMillis() / (maxSteps + 1L)));

        schemaValidator.validateInput(version.inputSchema(), command.input());
        var adapter = providerRegistry.require(provider.type());
        List<ModelProviderRequest.ToolDefinition> availableTools =
                toolRegistry.availableToolDefinitions(command.tenantCode());
        var capabilities = adapter.capabilities(provider.baseUrl());
        List<ModelProviderRequest.ToolDefinition> nativeTools =
                capabilities != null && capabilities.nativeToolCalling() ? availableTools : List.of();
        ModelProviderResponse plan = adapter.invoke(new ModelProviderRequest(
                provider.baseUrl(), credential, model,
                version.systemPrompt() + "\n\n" + PLANNER_INSTRUCTION,
                command.input(), phaseTimeout, command.traceId()));

        var steps = new ArrayList<AgentExecutor.StepResult>();
        steps.add(new AgentExecutor.StepResult("PLAN", "SUCCEEDED", null));
        String context = "计划：\n" + plan.content() + "\n\n用户请求：\n" + command.input();
        ModelProviderRequest.ToolResult previousToolResult = null;
        for (int step = 1; step <= maxSteps; step++) {
            ModelProviderResponse answer = adapter.invoke(new ModelProviderRequest(
                    provider.baseUrl(), credential, model,
                    version.systemPrompt() + "\n\n" + ANSWER_INSTRUCTION
                            + "\n\n可用工具契约：\n" + toolInstructions(availableTools),
                    context, phaseTimeout, command.traceId(), nativeTools, previousToolResult));
            ToolCall toolCall;
            try {
                toolCall = answer.toolCall() == null
                        ? parseToolCall(answer.content()) : parseToolCall(answer.toolCall());
            } catch (AgentExecutionException invalidToolCall) {
                answer = adapter.invoke(new ModelProviderRequest(
                        provider.baseUrl(), credential, model,
                        version.systemPrompt() + "\n\n" + TOOL_CALL_REPAIR_INSTRUCTION
                                + "\nRegistered tools: " + registeredTools(),
                    context + "\n\nPrevious invalid response:\n" + safeContent(answer),
                        phaseTimeout, command.traceId(), nativeTools, previousToolResult));
                toolCall = parseToolCall(answer.content());
            }
            if (toolCall == null) {
                OutputRepairResult repairResult = repairOutputIfRequired(adapter, provider.baseUrl(), version, model,
                        credential, answer,
                        context, phaseTimeout, command.traceId());
                answer = repairResult.response();
                if (repairResult.repaired()) {
                    steps.add(new AgentExecutor.StepResult("OUTPUT_REPAIR", "SUCCEEDED", null));
                }
                steps.add(new AgentExecutor.StepResult("VALIDATION", "SUCCEEDED", null));
                return new Result(provider.id(), model, answer, steps);
            }
            AgentTool.Result toolResult = toolRegistry.execute(
                    command.tenantCode(), toolCall.name(),
                    new AgentTool.Request(command.tenantCode(), toolCall.arguments(),
                            phaseTimeout, command.traceId(),
                            command.traceId() + ":" + step + ":" + toolCall.name(),
                            command.processInstanceId()));
            steps.add(new AgentExecutor.StepResult("TOOL_CALL", "SUCCEEDED", null));
            context = context + "\n\n工具 " + toolCall.name() + " 返回：\n" + toolResult.output();
            previousToolResult = toolCall.callId() == null ? null
                    : new ModelProviderRequest.ToolResult(
                            toolCall.callId(), toolCall.name(),
                            toolArgumentsJson(toolCall.arguments()), toolResult.output());
        }
        throw new IllegalStateException("Agent exceeded the maximum execution steps");
    }

    private OutputRepairResult repairOutputIfRequired(
            io.github.illuseahashmap.agent.provider.application.port.ModelProviderPort adapter,
            String baseUrl,
            io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion version,
            String model,
            String credential,
            ModelProviderResponse answer,
            String context,
            Duration phaseTimeout,
            String traceId
    ) {
        try {
            schemaValidator.validateOutput(version.outputSchema(), answer.content());
            return new OutputRepairResult(answer, false);
        } catch (AgentExecutionException invalidOutput) {
            if (!"AGENT_OUTPUT_NOT_JSON".equals(invalidOutput.failure().errorCode())
                    && !"AGENT_OUTPUT_SCHEMA_INVALID".equals(invalidOutput.failure().errorCode())) {
                throw invalidOutput;
            }
            ModelProviderResponse repaired = adapter.invoke(new ModelProviderRequest(
                    baseUrl, credential, model,
                    version.systemPrompt() + "\n\n"
                            + OUTPUT_REPAIR_INSTRUCTION.formatted(version.outputSchema()),
                    context + "\n\nPrevious invalid final response:\n" + answer.content(),
                    phaseTimeout, traceId));
            schemaValidator.validateOutput(version.outputSchema(), repaired.content());
            return new OutputRepairResult(repaired, true);
        }
    }

    private record OutputRepairResult(ModelProviderResponse response, boolean repaired) {
    }

    private String registeredTools() {
        return toolRegistry.registeredToolNames().stream().sorted().collect(Collectors.joining(", "));
    }

    private String toolInstructions(List<ModelProviderRequest.ToolDefinition> tools) {
        if (tools.isEmpty()) {
            return "当前没有可用工具。";
        }
        return tools.stream()
                .map(tool -> "- " + tool.name() + ": " + tool.description()
                        + "，参数 Schema=" + tool.inputSchema())
                .collect(Collectors.joining("\n"));
    }

    private ToolCall parseToolCall(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root == null || !"TOOL_CALL".equals(root.path("action").asText())) {
                return null;
            }
            String name = root.path("tool").asText();
            if (!StringUtils.hasText(name)) {
                throw new AgentExecutionException(new io.github.illuseahashmap.agent.runtime.domain.AgentFailure(
                        "AGENT_TOOL_CALL_INVALID",
                        io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory.TOOL_PROTOCOL,
                        false, io.github.illuseahashmap.agent.runtime.domain.ResultStatus.FAILED,
                        "Agent tool call must contain a tool name"));
            }
            JsonNode argumentsNode = root.path("arguments");
            if (!argumentsNode.isMissingNode() && !argumentsNode.isNull() && !argumentsNode.isObject()) {
                throw new AgentExecutionException(new io.github.illuseahashmap.agent.runtime.domain.AgentFailure(
                        "AGENT_TOOL_CALL_INVALID",
                        io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory.TOOL_PROTOCOL,
                        false, io.github.illuseahashmap.agent.runtime.domain.ResultStatus.FAILED,
                        "Agent tool call arguments must be an object"));
            }
            // Process executions inject runtime context such as processInstanceId;
            // the model may therefore omit arguments for tools that need no model-owned input.
            if (argumentsNode.isMissingNode() || argumentsNode.isNull()) {
                return new ToolCall(name, Map.of(), null);
            }
            Map<String, Object> arguments = objectMapper.convertValue(
                    argumentsNode, objectMapper.getTypeFactory()
                            .constructMapType(Map.class, String.class, Object.class));
            return new ToolCall(name, arguments, null);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private ToolCall parseToolCall(ModelProviderResponse.ToolCall call) {
        try {
            JsonNode arguments = objectMapper.readTree(call.argumentsJson());
            if (arguments == null || !arguments.isObject()) {
                throw new AgentExecutionException(new io.github.illuseahashmap.agent.runtime.domain.AgentFailure(
                        "AGENT_TOOL_CALL_INVALID",
                        io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory.TOOL_PROTOCOL,
                        false, io.github.illuseahashmap.agent.runtime.domain.ResultStatus.FAILED,
                        "Agent tool call arguments must be an object"));
            }
            return new ToolCall(call.name(), objectMapper.convertValue(arguments, Map.class), call.callId());
        } catch (JsonProcessingException exception) {
            throw new AgentExecutionException(new io.github.illuseahashmap.agent.runtime.domain.AgentFailure(
                    "AGENT_TOOL_CALL_INVALID",
                    io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory.TOOL_PROTOCOL,
                    false, io.github.illuseahashmap.agent.runtime.domain.ResultStatus.FAILED,
                    "Agent tool call arguments must be valid JSON"), exception);
        }
    }

    private String safeContent(ModelProviderResponse response) {
        return response.content() == null ? "" : response.content();
    }

    private String toolArgumentsJson(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (JsonProcessingException exception) {
            throw new AgentExecutionException(new io.github.illuseahashmap.agent.runtime.domain.AgentFailure(
                    "AGENT_TOOL_CALL_INVALID",
                    io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory.TOOL_PROTOCOL,
                    false, io.github.illuseahashmap.agent.runtime.domain.ResultStatus.FAILED,
                    "Agent tool call arguments could not be serialized"), exception);
        }
    }

    private record ToolCall(String name, Map<String, Object> arguments, String callId) {
    }
}

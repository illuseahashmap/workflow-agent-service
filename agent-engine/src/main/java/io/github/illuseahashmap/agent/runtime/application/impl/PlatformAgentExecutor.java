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
import io.github.illuseahashmap.agent.runtime.application.checkpoint.CheckpointContextPolicy;
import io.github.illuseahashmap.agent.runtime.application.port.AgentExecutor;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.agent.runtime.application.routing.AgentRouteDecision;
import io.github.illuseahashmap.agent.runtime.application.routing.AgentRouteDecisionParser;
import io.github.illuseahashmap.agent.runtime.application.routing.AgentRouteMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Set;
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

    private static final String ROUTER_INSTRUCTION = """
            You are the routing phase of a governed workflow Agent. Do not answer the request.
            Select exactly one mode from DIRECT, RAG, MCP, RAG_MCP, ABSTAIN.
            Only request tools from the supplied available tool list. DIRECT may use platform-native tools,
            but not tools marked RAG or MCP. ABSTAIN requests no tools.
            RAG requires at least one RAG capability. MCP requires at least one MCP capability.
            RAG_MCP requires both. Tool names carry no authority; use the supplied capability metadata.
            Return only one JSON object matching schema version 1 with at most five concise steps.
            Available tools: %s
            Required JSON Schema: %s
            """;

    private static final String ROUTE_REPAIR_INSTRUCTION = """
            The previous route decision violated the governed routing contract.
            Repair it once. Return only one JSON object matching the supplied schema.
            Never invent a tool and never include Markdown.
            Available tools: %s
            Required JSON Schema: %s
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
    private final AgentRouteDecisionParser routeParser;
    private final CheckpointContextPolicy checkpointContextPolicy;
    private final int maxSteps;

    @Autowired
    public PlatformAgentExecutor(
            AgentCredentialResolver credentialResolver,
            ModelProviderRegistry providerRegistry,
            AgentOutputSchemaValidator schemaValidator,
            AgentToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            AgentRouteDecisionParser routeParser,
            CheckpointContextPolicy checkpointContextPolicy,
            @Value("${workflow.agent.platform.max-steps:4}") int maxSteps
    ) {
        this.credentialResolver = credentialResolver;
        this.providerRegistry = providerRegistry;
        this.schemaValidator = schemaValidator;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.routeParser = routeParser;
        this.checkpointContextPolicy = checkpointContextPolicy;
        this.maxSteps = Math.max(1, Math.min(maxSteps, 20));
    }

    public PlatformAgentExecutor(
            AgentCredentialResolver credentialResolver,
            ModelProviderRegistry providerRegistry,
            AgentOutputSchemaValidator schemaValidator
    ) {
        this(credentialResolver, providerRegistry, schemaValidator,
                new AgentToolRegistry(java.util.List.of()), new ObjectMapper(),
                new AgentRouteDecisionParser(new ObjectMapper()), new CheckpointContextPolicy(40_000), 4);
    }

    public PlatformAgentExecutor(
            AgentCredentialResolver credentialResolver,
            ModelProviderRegistry providerRegistry,
            AgentOutputSchemaValidator schemaValidator,
            AgentToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            int maxSteps
    ) {
        this(credentialResolver, providerRegistry, schemaValidator, toolRegistry, objectMapper,
                new AgentRouteDecisionParser(objectMapper), new CheckpointContextPolicy(40_000), maxSteps);
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
        long deadlineNanos = System.nanoTime() + command.timeout().toNanos();

        schemaValidator.validateInput(version.inputSchema(), command.input());
        var adapter = providerRegistry.require(provider.type());
        var capabilities = adapter.capabilities(provider.baseUrl());
        List<ModelProviderRequest.ToolDefinition> effectiveTools =
                toolRegistry.availableToolDefinitions(
                        command.tenantCode(), version.toolSetJson(), command.nodeToolSetJson());
        List<ModelProviderRequest.ToolDefinition> nativeTools =
                capabilities != null && capabilities.nativeToolCalling() ? effectiveTools : List.of();
        var steps = new ArrayList<AgentExecutor.StepResult>();
        AgentExecutor.CheckpointState checkpoint = readCheckpoint(command.checkpointStateJson());
        String context;
        ModelProviderRequest.ToolResult previousToolResult;
        String previousToolResultReference;
        Set<String> routedToolNames;
        int firstToolStep;
        if (checkpoint == null) {
            Map<String, String> availableToolCapabilities = effectiveTools.stream()
                    .collect(Collectors.toUnmodifiableMap(
                            ModelProviderRequest.ToolDefinition::name,
                            ModelProviderRequest.ToolDefinition::capabilityType));
            ModelProviderResponse routeResponse = adapter.invoke(new ModelProviderRequest(
                    provider.baseUrl(), credential, model,
                    version.systemPrompt() + "\n\n" + ROUTER_INSTRUCTION.formatted(
                            availableToolCapabilities, AgentRouteDecisionParser.JSON_SCHEMA),
                    command.input(), remainingTimeout(deadlineNanos), command.traceId(),
                    List.of(), null,
                    new ModelProviderRequest.StructuredOutput(
                            "agent_route_decision_v1", AgentRouteDecisionParser.JSON_SCHEMA)));
            AgentRouteDecision route = parseOrRepairRoute(adapter, provider.baseUrl(), version.systemPrompt(),
                    credential, model, command, deadlineNanos, availableToolCapabilities, routeResponse);
            if (route.mode() == AgentRouteMode.ABSTAIN) {
                throw routeRejected(route.reason());
            }
            effectiveTools = restrictTools(effectiveTools, route.requestedTools());
            routedToolNames = Set.copyOf(route.requestedTools());
            nativeTools = capabilities != null && capabilities.nativeToolCalling() ? effectiveTools : List.of();
            context = checkpointContextPolicy.assembleInitial(serializeRoute(route), command.input());
            previousToolResult = null;
            previousToolResultReference = null;
            firstToolStep = 1;
            addStep(command, steps, "route:v1", "ROUTE", "SUCCEEDED", null,
                    checkpoint("route:v1", firstToolStep, context, null, null, routedToolNames));
        } else {
            context = checkpointContextPolicy.compact(checkpoint.context());
            previousToolResult = restoreToolResult(command.tenantCode(), checkpoint);
            previousToolResultReference = checkpoint.previousToolResultReference();
            routedToolNames = "1".equals(checkpoint.schemaVersion())
                    ? effectiveTools.stream().map(ModelProviderRequest.ToolDefinition::name)
                            .collect(Collectors.toUnmodifiableSet())
                    : Set.copyOf(checkpoint.routeTools());
            effectiveTools = effectiveTools.stream()
                    .filter(tool -> routedToolNames.contains(tool.name())).toList();
            nativeTools = capabilities != null && capabilities.nativeToolCalling() ? effectiveTools : List.of();
            firstToolStep = checkpoint.nextStep();
        }
        for (int step = firstToolStep; step <= maxSteps; step++) {
            ModelProviderResponse answer = adapter.invoke(new ModelProviderRequest(
                    provider.baseUrl(), credential, model,
                    version.systemPrompt() + "\n\n" + ANSWER_INSTRUCTION
                            + "\n\n可用工具契约：\n" + toolInstructions(effectiveTools),
                    context, remainingTimeout(deadlineNanos), command.traceId(), nativeTools, previousToolResult));
            ToolCall toolCall;
            try {
                toolCall = answer.toolCall() == null
                        ? parseToolCall(answer.content()) : parseToolCall(answer.toolCall());
            } catch (AgentExecutionException invalidToolCall) {
                answer = adapter.invoke(new ModelProviderRequest(
                        provider.baseUrl(), credential, model,
                        version.systemPrompt() + "\n\n" + TOOL_CALL_REPAIR_INSTRUCTION
                                + "\n可用工具契约：\n" + toolInstructions(effectiveTools),
                    context + "\n\nPrevious invalid response:\n" + safeContent(answer),
                        remainingTimeout(deadlineNanos), command.traceId(), nativeTools, previousToolResult));
                toolCall = parseToolCall(answer.content());
            }
            if (toolCall == null) {
                OutputRepairResult repairResult = repairOutputIfRequired(adapter, provider.baseUrl(), version, model,
                        credential, answer,
                        context, remainingTimeout(deadlineNanos), command.traceId());
                answer = repairResult.response();
                if (repairResult.repaired()) {
                    addStep(command, steps, "output-repair", "OUTPUT_REPAIR", "SUCCEEDED", null,
                            checkpoint("output-repair", step, context,
                                    serializeToolResultMetadata(previousToolResult), previousToolResultReference,
                                    routedToolNames));
                }
                addStep(command, steps, "validation", "VALIDATION", "SUCCEEDED", null,
                        checkpoint("validation", step, context,
                                serializeToolResultMetadata(previousToolResult), previousToolResultReference,
                                routedToolNames));
                return new Result(provider.id(), model, answer, steps,
                        command.progressListener() != null);
            }
            AgentTool.Result toolResult = toolRegistry.execute(
                    command.tenantCode(), version.toolSetJson(), command.nodeToolSetJson(), toolCall.name(),
                    new AgentTool.Request(command.tenantCode(), toolCall.arguments(),
                            remainingTimeout(deadlineNanos), command.traceId(),
                            command.runId() > 0 ? null : command.traceId() + ":tool:" + step + ":" + toolCall.name(),
                            command.processInstanceId(), command.runId(), "tool:" + step, version.id(),
                            command.requestedBy()));
            previousToolResultReference = toolResult.idempotencyKey();
            context = checkpointContextPolicy.appendToolResult(
                    context, toolCall.name(), toolResult.output(), previousToolResultReference);
            String callId = StringUtils.hasText(toolCall.callId())
                    ? toolCall.callId() : "tool-step-" + step;
            previousToolResult = new ModelProviderRequest.ToolResult(
                    callId, toolCall.name(), toolArgumentsJson(toolCall.arguments()), toolResult.output());
            addStep(command, steps, "tool:" + step, "TOOL_CALL", "SUCCEEDED", null,
                    checkpoint("tool:" + step, step + 1, context,
                            serializeToolResultMetadata(previousToolResult), previousToolResultReference,
                            routedToolNames));
        }
        throw new IllegalStateException("Agent exceeded the maximum execution steps");
    }

    private AgentRouteDecision parseOrRepairRoute(
            io.github.illuseahashmap.agent.provider.application.port.ModelProviderPort adapter,
            String baseUrl,
            String systemPrompt,
            String credential,
            String model,
            Command command,
            long deadlineNanos,
            Map<String, String> availableTools,
            ModelProviderResponse firstResponse
    ) {
        try {
            return routeParser.parse(firstResponse.content(), availableTools);
        } catch (AgentExecutionException firstFailure) {
            ModelProviderResponse repaired = adapter.invoke(new ModelProviderRequest(
                    baseUrl, credential, model,
                    systemPrompt + "\n\n" + ROUTE_REPAIR_INSTRUCTION.formatted(
                            availableTools, AgentRouteDecisionParser.JSON_SCHEMA),
                    command.input() + "\n\nPrevious invalid route:\n" + safeContent(firstResponse),
                    remainingTimeout(deadlineNanos), command.traceId(), List.of(), null,
                    new ModelProviderRequest.StructuredOutput(
                            "agent_route_decision_v1", AgentRouteDecisionParser.JSON_SCHEMA)));
            return routeParser.parse(repaired.content(), availableTools);
        }
    }

    private List<ModelProviderRequest.ToolDefinition> restrictTools(
            List<ModelProviderRequest.ToolDefinition> effectiveTools,
            List<String> requestedTools
    ) {
        Set<String> requested = Set.copyOf(requestedTools);
        return effectiveTools.stream().filter(tool -> requested.contains(tool.name())).toList();
    }

    private String serializeRoute(AgentRouteDecision route) {
        try {
            return objectMapper.writeValueAsString(route);
        } catch (JsonProcessingException exception) {
            throw routeRejected("Route decision could not be persisted");
        }
    }

    private AgentExecutionException routeRejected(String reason) {
        return new AgentExecutionException(new io.github.illuseahashmap.agent.runtime.domain.AgentFailure(
                "AGENT_ROUTE_NOT_EXECUTABLE",
                io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory.RESULT_POLICY,
                false, io.github.illuseahashmap.agent.runtime.domain.ResultStatus.REJECTED,
                StringUtils.hasText(reason) ? reason : "Agent route is not executable"));
    }

    private Duration remainingTimeout(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        return Duration.ofNanos(Math.max(1L, remainingNanos));
    }

    private void addStep(Command command, List<AgentExecutor.StepResult> steps,
                         String logicalStepId, String type, String status, String errorCode,
                         AgentExecutor.CheckpointState checkpoint) {
        AgentExecutor.StepResult result = new AgentExecutor.StepResult(type, status, errorCode, logicalStepId);
        steps.add(result);
        if (command.progressListener() != null) {
            command.progressListener().accept(steps.size(), new AgentExecutor.StepProgress(result, checkpoint));
        }
    }

    private AgentExecutor.CheckpointState readCheckpoint(String snapshotJson) {
        if (!StringUtils.hasText(snapshotJson)) {
            return null;
        }
        try {
            AgentExecutor.CheckpointState checkpoint = objectMapper.readValue(
                    snapshotJson, AgentExecutor.CheckpointState.class);
            if (!Set.of("1", "2", AgentExecutor.CheckpointState.CURRENT_SCHEMA_VERSION)
                    .contains(checkpoint.schemaVersion())) {
                throw checkpointFailure("Agent checkpoint schema version is unsupported", null);
            }
            if (!checkpointContextPolicy.matchesDigest(
                    checkpoint.context(), checkpoint.contextDigest())) {
                throw checkpointFailure("Agent checkpoint context digest does not match", null);
            }
            return checkpoint;
        } catch (JsonProcessingException exception) {
            throw checkpointFailure("Agent checkpoint could not be restored", exception);
        }
    }

    private ModelProviderRequest.ToolResult readToolResult(String snapshotJson) {
        if (!StringUtils.hasText(snapshotJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshotJson, ModelProviderRequest.ToolResult.class);
        } catch (JsonProcessingException exception) {
            throw checkpointFailure("Agent checkpoint tool result could not be restored", exception);
        }
    }

    private ModelProviderRequest.ToolResult restoreToolResult(
            String tenantCode, AgentExecutor.CheckpointState checkpoint
    ) {
        ModelProviderRequest.ToolResult metadata = readToolResult(checkpoint.previousToolResultJson());
        if (metadata == null || !"3".equals(checkpoint.schemaVersion())
                || !StringUtils.hasText(checkpoint.previousToolResultReference())) {
            return metadata;
        }
        String output = toolRegistry.restoreSucceededOutput(
                        tenantCode, metadata.toolName(), checkpoint.previousToolResultReference())
                .orElseThrow(() -> checkpointFailure(
                        "Agent checkpoint tool result reference could not be restored", null));
        return new ModelProviderRequest.ToolResult(
                metadata.callId(), metadata.toolName(), metadata.argumentsJson(), output);
    }

    private String serializeToolResult(ModelProviderRequest.ToolResult result) {
        if (result == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw checkpointFailure("Agent checkpoint tool result could not be serialized", exception);
        }
    }

    private String serializeToolResultMetadata(ModelProviderRequest.ToolResult result) {
        if (result == null) {
            return null;
        }
        return serializeToolResult(new ModelProviderRequest.ToolResult(
                result.callId(), result.toolName(), result.argumentsJson(), ""));
    }

    private AgentExecutor.CheckpointState checkpoint(
            String logicalStepId,
            int nextStep,
            String context,
            String previousToolResultJson,
            String previousToolResultReference,
            Set<String> routeTools
    ) {
        String compacted = checkpointContextPolicy.compact(context);
        return new AgentExecutor.CheckpointState(
                AgentExecutor.CheckpointState.CURRENT_SCHEMA_VERSION,
                logicalStepId, nextStep, compacted, checkpointContextPolicy.digest(compacted),
                previousToolResultJson, previousToolResultReference,
                routeTools.stream().sorted().toList());
    }

    private AgentExecutionException checkpointFailure(String message, Exception cause) {
        return new AgentExecutionException(new io.github.illuseahashmap.agent.runtime.domain.AgentFailure(
                "AGENT_CHECKPOINT_INVALID",
                io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory.EXECUTION_UNEXPECTED,
                false, io.github.illuseahashmap.agent.runtime.domain.ResultStatus.FAILED, message), cause);
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

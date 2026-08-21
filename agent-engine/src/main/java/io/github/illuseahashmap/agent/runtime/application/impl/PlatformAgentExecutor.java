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
import io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry;
import io.github.illuseahashmap.agent.runtime.application.port.AgentExecutor;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
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
        ModelProviderResponse plan = adapter.invoke(new ModelProviderRequest(
                provider.baseUrl(), credential, model,
                version.systemPrompt() + "\n\n" + PLANNER_INSTRUCTION,
                command.input(), phaseTimeout, command.traceId()));

        var steps = new ArrayList<AgentExecutor.StepResult>();
        steps.add(new AgentExecutor.StepResult("PLAN", "SUCCEEDED", null));
        String context = "计划：\n" + plan.content() + "\n\n用户请求：\n" + command.input();
        for (int step = 1; step <= maxSteps; step++) {
            ModelProviderResponse answer = adapter.invoke(new ModelProviderRequest(
                    provider.baseUrl(), credential, model,
                    version.systemPrompt() + "\n\n" + ANSWER_INSTRUCTION,
                    context, phaseTimeout, command.traceId()));
            ToolCall toolCall = parseToolCall(answer.content());
            if (toolCall == null) {
                schemaValidator.validateOutput(version.outputSchema(), answer.content());
                steps.add(new AgentExecutor.StepResult("VALIDATION", "SUCCEEDED", null));
                return new Result(provider.id(), model, answer, steps);
            }
            AgentTool.Result toolResult = toolRegistry.execute(
                    command.tenantCode(), toolCall.name(),
                    new AgentTool.Request(command.tenantCode(), toolCall.arguments(),
                            phaseTimeout, command.traceId(),
                            command.traceId() + ":" + step + ":" + toolCall.name()));
            steps.add(new AgentExecutor.StepResult("TOOL_CALL", "SUCCEEDED", null));
            context = context + "\n\n工具 " + toolCall.name() + " 返回：\n" + toolResult.output();
        }
        throw new IllegalStateException("Agent exceeded the maximum execution steps");
    }

    private ToolCall parseToolCall(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root == null || !"TOOL_CALL".equals(root.path("action").asText())) {
                return null;
            }
            String name = root.path("tool").asText();
            if (!StringUtils.hasText(name) || !root.path("arguments").isObject()) {
                throw new IllegalArgumentException("Invalid Agent tool call");
            }
            Map<String, Object> arguments = objectMapper.convertValue(
                    root.path("arguments"), objectMapper.getTypeFactory()
                            .constructMapType(Map.class, String.class, Object.class));
            return new ToolCall(name, arguments);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private record ToolCall(String name, Map<String, Object> arguments) {
    }
}

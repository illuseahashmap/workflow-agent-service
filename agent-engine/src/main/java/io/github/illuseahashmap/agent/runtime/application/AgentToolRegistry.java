package io.github.illuseahashmap.agent.runtime.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.agent.definition.application.port.AgentToolCatalogPort;
import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;
import io.github.illuseahashmap.agent.runtime.application.port.AgentToolDefinition;
import io.github.illuseahashmap.agent.runtime.application.port.AgentToolExecutionAuditRepository;
import io.github.illuseahashmap.agent.runtime.application.port.AgentToolPolicyRepository;
import io.github.illuseahashmap.agent.runtime.application.port.AgentToolResolver;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailure;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Central policy boundary for tenant authorization, schema validation and tool idempotency. */
@Component
public class AgentToolRegistry implements AgentToolCatalogPort {

    private static final int MAX_OUTPUT_CHARS = 20_000;

    private final Map<String, AgentTool> tools;
    private final AgentToolPolicyRepository policyRepository;
    private final AgentToolExecutionAuditRepository auditRepository;
    private final AgentOutputSchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;
    private final List<AgentToolResolver> resolvers;

    @Autowired
    public AgentToolRegistry(
            List<AgentTool> tools,
            AgentToolPolicyRepository policyRepository,
            AgentToolExecutionAuditRepository auditRepository,
            AgentOutputSchemaValidator schemaValidator,
            ObjectMapper objectMapper,
            List<AgentToolResolver> resolvers
    ) {
        this.tools = index(tools);
        this.policyRepository = policyRepository;
        this.auditRepository = auditRepository;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
        this.resolvers = List.copyOf(resolvers);
    }

    /** Compatibility constructor for isolated executor tests. */
    public AgentToolRegistry(List<AgentTool> tools) {
        this(tools, AgentToolPolicyRepository.ALLOW_ALL,
                AgentToolExecutionAuditRepository.NOOP,
                new AgentOutputSchemaValidator(new ObjectMapper()), new ObjectMapper(), List.of());
    }

    /** Compatibility constructor for policy and audit unit tests. */
    public AgentToolRegistry(List<AgentTool> tools, AgentToolPolicyRepository policyRepository,
                             AgentToolExecutionAuditRepository auditRepository,
                             AgentOutputSchemaValidator schemaValidator, ObjectMapper objectMapper) {
        this(tools, policyRepository, auditRepository, schemaValidator, objectMapper, List.of());
    }

    public AgentTool require(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            tool = resolvers.stream().map(resolver -> resolver.resolve(name)).flatMap(Optional::stream)
                    .findFirst().orElse(null);
        }
        if (tool == null) {
            throw toolProtocol("AGENT_TOOL_NOT_REGISTERED", "Agent tool is not registered: " + name);
        }
        return tool;
    }

    public Set<String> registeredToolNames() {
        return tools.keySet();
    }

    @Override
    public void validatePublication(String tenantCode, AgentExecutionMode executionMode, String toolSetJson) {
        try {
            JsonNode root = objectMapper.readTree(toolSetJson);
            if (root == null || !root.isArray()) {
                throw new io.github.illuseahashmap.workflow.shared.exception.BusinessException(
                        io.github.illuseahashmap.workflow.shared.exception.ErrorCode.BAD_REQUEST,
                        "Agent tool set must be a JSON array");
            }
            List<String> invalid = new java.util.ArrayList<>();
            if (root.size() > 0 && executionMode != AgentExecutionMode.PLATFORM_AGENT) {
                invalid.add("tool set requires PLATFORM_AGENT execution mode");
            }
            root.forEach(node -> {
                if (!node.isTextual() || !StringUtils.hasText(node.asText())) {
                    invalid.add("blank or non-text tool code");
                    return;
                }
                String toolCode = node.asText().trim();
                try {
                    require(toolCode);
                } catch (AgentExecutionException exception) {
                    invalid.add(toolCode + " is not registered");
                    return;
                }
                AgentToolDefinition definition = policyRepository.findAuthorized(tenantCode, toolCode).orElse(null);
                if (definition == null) {
                    invalid.add(toolCode + " is not authorized for the tenant");
                    return;
                }
                if (!StringUtils.hasText(definition.inputSchema())) {
                    invalid.add(toolCode + " has no input Schema");
                    return;
                }
                try {
                    schemaValidator.validateDefinition(definition.inputSchema());
                } catch (AgentExecutionException exception) {
                    invalid.add(toolCode + " has an invalid input Schema");
                }
            });
            if (!invalid.isEmpty()) {
                throw new io.github.illuseahashmap.workflow.shared.exception.BusinessException(
                        io.github.illuseahashmap.workflow.shared.exception.ErrorCode.BAD_REQUEST,
                        "Agent tool set is invalid: " + String.join(", ", invalid));
            }
        } catch (io.github.illuseahashmap.workflow.shared.exception.BusinessException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new io.github.illuseahashmap.workflow.shared.exception.BusinessException(
                    io.github.illuseahashmap.workflow.shared.exception.ErrorCode.BAD_REQUEST,
                    "Agent tool set is invalid", exception);
        }
    }

    public List<ModelProviderRequest.ToolDefinition> availableToolDefinitions(String tenantCode) {
        return policyRepository.findAuthorized(tenantCode, tools.keySet()).stream()
                .filter(definition -> isRegistered(definition.toolCode()))
                .map(definition -> new ModelProviderRequest.ToolDefinition(
                        definition.toolCode(), definition.toolName(), definition.inputSchema()))
                .toList();
    }

    /** Returns the immutable AgentVersion set intersected with the BPMN node set and tenant grants. */
    public List<ModelProviderRequest.ToolDefinition> availableToolDefinitions(
            String tenantCode, String versionToolSetJson, String nodeToolSetJson) {
        Set<String> versionTools = parseToolSet(versionToolSetJson, "AgentVersion tool set");
        Set<String> nodeTools = StringUtils.hasText(nodeToolSetJson)
                ? parseToolSet(nodeToolSetJson, "BPMN node tool set") : versionTools;
        Set<String> effectiveTools = versionTools.stream()
                .filter(nodeTools::contains)
                .collect(Collectors.toUnmodifiableSet());
        return policyRepository.findAuthorized(tenantCode, effectiveTools).stream()
                .filter(definition -> isRegistered(definition.toolCode()))
                .map(definition -> new ModelProviderRequest.ToolDefinition(
                        definition.toolCode(), definition.toolName(), definition.inputSchema()))
                .toList();
    }

    public AgentTool.Result execute(String tenantCode, String toolCode, AgentTool.Request request) {
        return execute(tenantCode, null, null, toolCode, request);
    }

    public AgentTool.Result execute(
            String tenantCode,
            String versionToolSetJson,
            String nodeToolSetJson,
            String toolCode,
            AgentTool.Request request
    ) {
        AgentTool tool = require(toolCode);
        if (versionToolSetJson != null
                && !availableToolCodes(versionToolSetJson, nodeToolSetJson).contains(toolCode)) {
            throw toolProtocol("AGENT_TOOL_NOT_BOUND_TO_VERSION",
                    "Agent tool is not bound to the current Agent version/node: " + toolCode);
        }
        AgentToolDefinition definition = policyRepository.findAuthorized(tenantCode, toolCode)
                .orElseThrow(() -> toolProtocol("AGENT_TOOL_NOT_AUTHORIZED",
                        "Agent tool is not authorized for the current tenant: " + toolCode));
        if (!definition.readOnly() || !tool.readOnly() || !StringUtils.hasText(definition.inputSchema())) {
            throw toolProtocol("AGENT_TOOL_POLICY_INVALID", "Agent tool policy is invalid: " + toolCode);
        }
        String argumentsJson = serialize(request.arguments());
        schemaValidator.validateInput(definition.inputSchema(), argumentsJson);
        String argumentsHash = hash(argumentsJson);
        String idempotencyKey = stableIdempotencyKey(request, toolCode, argumentsHash);
        if (!StringUtils.hasText(idempotencyKey)) {
            throw toolProtocol("AGENT_TOOL_IDEMPOTENCY_KEY_REQUIRED", "Agent tool idempotency key is required");
        }
        var now = Instant.now();
        var claim = auditRepository.tryClaim(new AgentToolExecutionAuditRepository.Audit(
                tenantCode, toolCode, idempotencyKey, argumentsHash, "RUNNING", null,
                null, request.traceId(), now), request.traceId(),
                now.plus(request.timeout()), now);
        if (!claim.acquired()) {
            var audit = claim.audit();
            if (!audit.argumentsHash().equals(argumentsHash)) {
                throw toolProtocol("AGENT_TOOL_IDEMPOTENCY_CONFLICT",
                        "Agent tool idempotency key was reused with different arguments");
            }
            if ("SUCCEEDED".equals(audit.status())) {
                return new AgentTool.Result(audit.output(), idempotencyKey);
            }
            if ("UNKNOWN".equals(audit.status())) {
                throw toolProtocol("AGENT_TOOL_EXECUTION_UNKNOWN",
                        "Agent tool execution outcome requires manual verification");
            }
            throw toolProtocol("AGENT_TOOL_EXECUTION_IN_PROGRESS",
                    "Agent tool execution is already claimed by another worker");
        }

        try {
            AgentTool.Result result = tool.execute(request.withIdempotencyKey(idempotencyKey));
            if (result == null || result.output() == null || result.output().length() > MAX_OUTPUT_CHARS) {
                throw toolProtocol("AGENT_TOOL_OUTPUT_INVALID", "Agent tool output is empty or too large");
            }
            auditRepository.complete(new AgentToolExecutionAuditRepository.Audit(
                    tenantCode, toolCode, idempotencyKey, argumentsHash, "SUCCEEDED",
                    result.output(), null, request.traceId(), Instant.now()), request.traceId());
            return new AgentTool.Result(result.output(), idempotencyKey);
        } catch (RuntimeException exception) {
            auditRepository.complete(new AgentToolExecutionAuditRepository.Audit(
                    tenantCode, toolCode, idempotencyKey, argumentsHash, "FAILED", null,
                    "AGENT_TOOL_EXECUTION_FAILED", request.traceId(), Instant.now()), request.traceId());
            throw exception;
        }
    }

    private String stableIdempotencyKey(AgentTool.Request request, String toolCode, String argumentsHash) {
        if (request.runId() > 0 && StringUtils.hasText(request.logicalStepId())) {
            return request.runId() + ":" + request.logicalStepId().trim()
                    + ":" + toolCode;
        }
        return request.idempotencyKey();
    }

    private Set<String> availableToolCodes(String versionToolSetJson, String nodeToolSetJson) {
        Set<String> versionTools = parseToolSet(versionToolSetJson, "AgentVersion tool set");
        if (!StringUtils.hasText(nodeToolSetJson)) {
            return versionTools;
        }
        versionTools.retainAll(parseToolSet(nodeToolSetJson, "BPMN node tool set"));
        return versionTools;
    }

    private Set<String> parseToolSet(String json, String label) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!StringUtils.hasText(json) || root == null || !root.isArray()) {
                throw toolProtocol("AGENT_TOOL_SET_INVALID", label + " must be a JSON array");
            }
            Set<String> result = new java.util.LinkedHashSet<>();
            for (var item : root) {
                if (!item.isTextual() || !StringUtils.hasText(item.asText())) {
                    throw toolProtocol("AGENT_TOOL_SET_INVALID", label + " contains an invalid tool code");
                }
                result.add(item.asText());
            }
            return result;
        } catch (AgentExecutionException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw toolProtocol("AGENT_TOOL_SET_INVALID", label + " must be valid JSON");
        }
    }

    private Map<String, AgentTool> index(List<AgentTool> registeredTools) {
        return registeredTools.stream().collect(Collectors.toUnmodifiableMap(
                AgentTool::name,
                Function.identity(),
                (left, right) -> {
                    throw new IllegalStateException("Duplicate Agent tool: " + left.name());
                }));
    }

    private boolean isRegistered(String toolCode) {
        return tools.containsKey(toolCode) || resolvers.stream()
                .anyMatch(resolver -> resolver.resolve(toolCode).isPresent());
    }

    private String serialize(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (JsonProcessingException exception) {
            throw new AgentExecutionException(new AgentFailure(
                    "AGENT_TOOL_ARGUMENTS_INVALID", AgentFailureCategory.TOOL_PROTOCOL,
                    false, ResultStatus.FAILED, "Agent tool arguments must be JSON"), exception);
        }
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AgentExecutionException(new AgentFailure(
                    "AGENT_TOOL_HASH_ERROR", AgentFailureCategory.EXECUTION_UNEXPECTED,
                    false, ResultStatus.FAILED, "Unable to hash Agent tool arguments"), exception);
        }
    }

    private AgentExecutionException toolProtocol(String errorCode, String message) {
        return new AgentExecutionException(new AgentFailure(
                errorCode, AgentFailureCategory.TOOL_PROTOCOL, false,
                ResultStatus.FAILED, message));
    }
}

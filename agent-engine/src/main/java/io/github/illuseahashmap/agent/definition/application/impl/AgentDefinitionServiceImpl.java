package io.github.illuseahashmap.agent.definition.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.application.AgentDefinitionService;
import io.github.illuseahashmap.agent.definition.application.dto.AgentDefinitionCommand;
import io.github.illuseahashmap.agent.definition.application.dto.AgentDefinitionView;
import io.github.illuseahashmap.agent.definition.application.dto.AgentVersionCommand;
import io.github.illuseahashmap.agent.definition.application.dto.AgentVersionView;
import io.github.illuseahashmap.agent.definition.application.dto.PublishedAgentVersionView;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinition;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionRepository;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersionRepository;
import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;
import io.github.illuseahashmap.agent.definition.domain.AgentVersionStatus;
import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderRepository;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.agent.runtime.application.AgentExecutionException;
import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry;
import io.github.illuseahashmap.agent.runtime.application.port.AgentToolDefinition;
import io.github.illuseahashmap.agent.runtime.application.port.AgentToolPolicyRepository;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AgentDefinitionServiceImpl implements AgentDefinitionService {

    private final AgentDefinitionRepository definitionRepository;
    private final AgentDefinitionVersionRepository versionRepository;
    private final AgentProviderRepository providerRepository;
    private final TenantProvider tenantProvider;
    private final CurrentPrincipalProvider principalProvider;
    private final ObjectMapper objectMapper;
    private final AgentOutputSchemaValidator outputSchemaValidator;
    private final AgentToolRegistry toolRegistry;
    private final AgentToolPolicyRepository toolPolicyRepository;

    @Autowired
    public AgentDefinitionServiceImpl(
            AgentDefinitionRepository definitionRepository,
            AgentDefinitionVersionRepository versionRepository,
            AgentProviderRepository providerRepository,
            TenantProvider tenantProvider,
            CurrentPrincipalProvider principalProvider,
            ObjectMapper objectMapper,
            AgentOutputSchemaValidator outputSchemaValidator,
            AgentToolRegistry toolRegistry,
            AgentToolPolicyRepository toolPolicyRepository
    ) {
        this.definitionRepository = definitionRepository;
        this.versionRepository = versionRepository;
        this.providerRepository = providerRepository;
        this.tenantProvider = tenantProvider;
        this.principalProvider = principalProvider;
        this.objectMapper = objectMapper;
        this.outputSchemaValidator = outputSchemaValidator;
        this.toolRegistry = toolRegistry;
        this.toolPolicyRepository = toolPolicyRepository;
    }

    public AgentDefinitionServiceImpl(
            AgentDefinitionRepository definitionRepository,
            AgentDefinitionVersionRepository versionRepository,
            AgentProviderRepository providerRepository,
            TenantProvider tenantProvider,
            CurrentPrincipalProvider principalProvider,
            ObjectMapper objectMapper
    ) {
        this(definitionRepository, versionRepository, providerRepository, tenantProvider,
                principalProvider, objectMapper, new AgentOutputSchemaValidator(objectMapper),
                new AgentToolRegistry(List.of()), AgentToolPolicyRepository.ALLOW_ALL);
    }

    @Override
    public PageResult<AgentDefinitionView> page(
            Integer pageNum,
            Integer pageSize,
            String keyword,
            Boolean enabled
    ) {
        int normalizedPageNum = normalizePageNum(pageNum);
        int normalizedPageSize = normalizePageSize(pageSize);
        String tenantCode = tenantCode();
        PageSlice<AgentDefinition> page = definitionRepository.page(new AgentDefinitionRepository.PageCriteria(
                normalizedPageNum, normalizedPageSize, tenantCode, normalizeNullable(keyword), enabled));
        return new PageResult<>(page.total(), page.pageNumber(), page.pageSize(),
                page.items().stream().map(this::toView).toList());
    }

    @Override
    public PageResult<PublishedAgentVersionView> publishedVersions(
            Integer pageNum, Integer pageSize, String keyword, Long versionId
    ) {
        int normalizedPageNum = normalizePageNum(pageNum);
        int normalizedPageSize = normalizePageSize(pageSize);
        PageSlice<AgentDefinitionVersionRepository.PublishedVersion> page =
                versionRepository.pagePublished(
                        new AgentDefinitionVersionRepository.PublishedVersionCriteria(
                                normalizedPageNum, normalizedPageSize, tenantCode(),
                                normalizeNullable(keyword), versionId));
        return new PageResult<>(page.total(), page.pageNumber(), page.pageSize(),
                page.items().stream().map(this::toPublishedVersionView).toList());
    }

    private PublishedAgentVersionView toPublishedVersionView(
            AgentDefinitionVersionRepository.PublishedVersion version
    ) {
        return new PublishedAgentVersionView(
                version.id(), version.definitionId(), version.agentCode(), version.agentName(),
                version.version(), version.executionMode(), version.timeoutSeconds(),
                version.inputSchema(), version.outputSchema(), contractFingerprint(version));
    }

    private String contractFingerprint(AgentDefinitionVersionRepository.PublishedVersion version) {
        try {
            String contract = version.executionMode() + "\n" + version.timeoutSeconds() + "\n"
                    + nullToEmpty(version.inputSchema()) + "\n" + nullToEmpty(version.outputSchema());
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(contract.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentDefinitionView create(AgentDefinitionCommand command) {
        String tenantCode = tenantCode();
        String code = command.code().trim();
        assertUniqueCode(tenantCode, code, null);
        AgentDefinition saved = definitionRepository.save(new AgentDefinition(
                null, tenantCode, code, command.name().trim(), normalizeNullable(command.description()),
                command.enabled(), null, null));
        createInitialDraft(saved);
        return toView(saved);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentDefinitionView update(long id, AgentDefinitionCommand command) {
        String tenantCode = tenantCode();
        AgentDefinition existing = requireDefinition(tenantCode, id);
        if (!existing.code().equals(command.code().trim())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent code cannot be changed");
        }
        AgentDefinition updated = new AgentDefinition(
                id, tenantCode, existing.code(), command.name().trim(), normalizeNullable(command.description()),
                command.enabled(), existing.createdAt(), existing.updatedAt());
        definitionRepository.update(updated);
        return toView(requireDefinition(tenantCode, id));
    }

    @Override
    public List<AgentVersionView> versions(long definitionId) {
        String tenantCode = tenantCode();
        requireDefinition(tenantCode, definitionId);
        return versionRepository.findByDefinition(tenantCode, definitionId).stream()
                .map(this::toVersionView)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentVersionView createDraft(long definitionId) {
        String tenantCode = tenantCode();
        requireDefinition(tenantCode, definitionId);
        AgentDefinitionVersion latest = versionRepository.findLatest(tenantCode, definitionId).orElse(null);
        if (latest != null && !latest.published()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Complete or publish the existing draft first");
        }
        AgentDefinitionVersion draft = latest == null
                ? newDraft(definitionId, 1, null)
                : newDraft(definitionId, latest.version() + 1, latest);
        return toVersionView(versionRepository.save(draft));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentVersionView updateDraft(long definitionId, long versionId, AgentVersionCommand command) {
        String tenantCode = tenantCode();
        requireDefinition(tenantCode, definitionId);
        AgentDefinitionVersion existing = requireVersion(tenantCode, definitionId, versionId);
        if (existing.published()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Published Agent versions are immutable");
        }
        validateInputSchema(command.inputSchema());
        validateOutputSchema(command.outputSchema());
        validateProvider(tenantCode, command.providerId(), false);
        AgentDefinitionVersion updated = new AgentDefinitionVersion(
                existing.id(), tenantCode, definitionId, existing.version(), AgentVersionStatus.DRAFT,
                command.executionMode(),
                command.providerId(), normalizeNullable(command.modelName()),
                command.systemPrompt() == null ? "" : command.systemPrompt().trim(), command.timeoutSeconds(),
                command.failurePolicy(), normalizeNullable(command.inputSchema()),
                normalizeNullable(command.outputSchema()), existing.createdBy(),
                null, null, existing.createdAt(), existing.updatedAt(), toolSetJson(command.toolCodes()));
        versionRepository.updateDraft(updated);
        return toVersionView(requireVersion(tenantCode, definitionId, versionId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentVersionView publish(long definitionId, long versionId) {
        String tenantCode = tenantCode();
        AgentDefinition definition = requireDefinition(tenantCode, definitionId);
        if (!definition.enabled()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Enable the Agent definition before publishing");
        }
        AgentDefinitionVersion version = requireVersion(tenantCode, definitionId, versionId);
        if (version.published()) {
            return toVersionView(version);
        }
        if (version.executionMode() != AgentExecutionMode.MODEL_ONLY
                && version.executionMode() != AgentExecutionMode.PLATFORM_AGENT) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "The selected Agent execution mode is not available yet");
        }
        AgentProvider provider = validateProvider(tenantCode, version.providerId(), true);
        String effectiveModel = StringUtils.hasText(version.modelName())
                ? version.modelName()
                : provider.defaultModel();
        if (provider.type() == AgentProviderType.OPENAI_COMPATIBLE && !StringUtils.hasText(effectiveModel)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "A model is required before publishing");
        }
        if (!StringUtils.hasText(version.systemPrompt())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "System prompt is required before publishing");
        }
        validateOutputSchema(version.outputSchema());
        validateInputSchema(version.inputSchema());
        validateToolSet(version.toolSetJson(), version.executionMode());
        versionRepository.publish(tenantCode, definitionId, versionId, principalProvider.current().principalId());
        return toVersionView(requireVersion(tenantCode, definitionId, versionId));
    }

    private void createInitialDraft(AgentDefinition definition) {
        versionRepository.save(newDraft(definition.id(), 1, null));
    }

    private AgentDefinitionVersion newDraft(long definitionId, int version, AgentDefinitionVersion source) {
        return new AgentDefinitionVersion(
                null,
                tenantCode(),
                definitionId,
                version,
                AgentVersionStatus.DRAFT,
                source == null ? io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode.MODEL_ONLY
                        : source.executionMode(),
                source == null ? null : source.providerId(),
                source == null ? null : source.modelName(),
                source == null ? "" : source.systemPrompt(),
                source == null ? 120 : source.timeoutSeconds(),
                source == null ? io.github.illuseahashmap.agent.definition.domain.AgentFailurePolicy.FAIL_PROCESS
                        : source.failurePolicy(),
                source == null ? null : source.inputSchema(),
                source == null ? null : source.outputSchema(),
                principalProvider.current().principalId(),
                null,
                null,
                null,
                null,
                source == null ? "[]" : source.toolSetJson());
    }

    private String toolSetJson(List<String> toolCodes) {
        try {
            var normalized = new LinkedHashSet<String>();
            for (String toolCode : toolCodes == null ? List.<String>of() : toolCodes) {
                if (toolCode == null || toolCode.isBlank()) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent tool code must not be blank");
                }
                normalized.add(toolCode.trim());
            }
            return objectMapper.writeValueAsString(normalized);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent tool set is invalid", exception);
        }
    }

    private void validateToolSet(String toolSetJson, AgentExecutionMode executionMode) {
        try {
            JsonNode root = objectMapper.readTree(toolSetJson);
            if (root == null || !root.isArray()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent tool set must be a JSON array");
            }
            List<String> invalid = new ArrayList<>();
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
                    toolRegistry.require(toolCode);
                } catch (AgentExecutionException exception) {
                    invalid.add(toolCode + " is not registered");
                    return;
                }
                AgentToolDefinition definition = toolPolicyRepository
                        .findAuthorized(tenantCode(), toolCode).orElse(null);
                if (definition == null) {
                    invalid.add(toolCode + " is not authorized for the tenant");
                    return;
                }
                if (!StringUtils.hasText(definition.inputSchema())) {
                    invalid.add(toolCode + " has no input Schema");
                    return;
                }
                try {
                    outputSchemaValidator.validateDefinition(definition.inputSchema());
                } catch (AgentExecutionException exception) {
                    invalid.add(toolCode + " has an invalid input Schema");
                }
            });
            if (!invalid.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "Agent tool set is invalid: " + String.join(", ", invalid));
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent tool set is invalid", exception);
        }
    }

    private AgentProvider validateProvider(String tenantCode, Long providerId, boolean publishing) {
        if (providerId == null) {
            if (publishing) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Provider is required before publishing");
            }
            return null;
        }
        AgentProvider provider = providerRepository.findById(tenantCode, providerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent Provider does not exist"));
        if (publishing && !provider.enabled()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Selected Agent Provider is disabled");
        }
        if (publishing && provider.type() == AgentProviderType.OPENAI_COMPATIBLE
                && !provider.credentialConfigured()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Provider credential is required before publishing");
        }
        return provider;
    }

    private void validateOutputSchema(String outputSchema) {
        if (!StringUtils.hasText(outputSchema)) {
            return;
        }
        try {
            outputSchemaValidator.validateDefinition(outputSchema);
        } catch (AgentExecutionException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Output Schema must be valid JSON", exception);
        }
    }

    private void validateInputSchema(String inputSchema) {
        if (!StringUtils.hasText(inputSchema)) {
            return;
        }
        try {
            outputSchemaValidator.validateDefinition(inputSchema);
        } catch (AgentExecutionException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Input Schema must be valid JSON", exception);
        }
    }

    private AgentDefinition requireDefinition(String tenantCode, long id) {
        return definitionRepository.findById(tenantCode, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent definition does not exist"));
    }

    private AgentDefinitionVersion requireVersion(String tenantCode, long definitionId, long versionId) {
        return versionRepository.findById(tenantCode, definitionId, versionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent version does not exist"));
    }

    private void assertUniqueCode(String tenantCode, String code, Long excludedId) {
        if (definitionRepository.existsByCode(tenantCode, code, excludedId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Agent code already exists in the current tenant");
        }
    }

    private AgentDefinitionView toView(AgentDefinition definition) {
        List<AgentDefinitionVersion> versions = versionRepository.findByDefinition(
                definition.tenantCode(), definition.id());
        Integer latestVersion = versions.isEmpty() ? null : versions.getFirst().version();
        Integer publishedVersion = versions.stream()
                .filter(AgentDefinitionVersion::published)
                .map(AgentDefinitionVersion::version)
                .findFirst()
                .orElse(null);
        return new AgentDefinitionView(
                definition.id(), definition.code(), definition.name(), definition.description(), definition.enabled(),
                latestVersion, publishedVersion, definition.createdAt(), definition.updatedAt());
    }

    private AgentVersionView toVersionView(AgentDefinitionVersion version) {
        String providerName = version.providerId() == null ? null
                : providerRepository.findById(version.tenantCode(), version.providerId())
                        .map(AgentProvider::name)
                        .orElse(null);
        return new AgentVersionView(
                version.id(), version.definitionId(), version.version(), version.status(), version.executionMode(),
                version.providerId(),
                providerName, version.modelName(), version.systemPrompt(), version.timeoutSeconds(),
                version.failurePolicy(), version.inputSchema(), version.outputSchema(), version.createdBy(),
                version.publishedBy(),
                version.publishedAt(), version.createdAt(), version.updatedAt(), parseToolCodes(version.toolSetJson()));
    }

    private List<String> parseToolCodes(String toolSetJson) {
        try {
            JsonNode root = objectMapper.readTree(toolSetJson);
            if (root == null || !root.isArray()) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            root.forEach(node -> {
                if (node.isTextual() && StringUtils.hasText(node.asText())) {
                    result.add(node.asText());
                }
            });
            return List.copyOf(result);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String tenantCode() {
        return tenantProvider.current().tenantCode();
    }

    private int normalizePageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private int normalizePageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}

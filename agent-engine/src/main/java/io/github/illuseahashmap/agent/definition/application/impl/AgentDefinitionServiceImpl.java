package io.github.illuseahashmap.agent.definition.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.application.AgentDefinitionService;
import io.github.illuseahashmap.agent.definition.application.dto.AgentDefinitionCommand;
import io.github.illuseahashmap.agent.definition.application.dto.AgentDefinitionView;
import io.github.illuseahashmap.agent.definition.application.dto.AgentVersionCommand;
import io.github.illuseahashmap.agent.definition.application.dto.AgentVersionView;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinition;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionRepository;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersionRepository;
import io.github.illuseahashmap.agent.definition.domain.AgentVersionStatus;
import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderRepository;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.List;
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

    @Autowired
    public AgentDefinitionServiceImpl(
            AgentDefinitionRepository definitionRepository,
            AgentDefinitionVersionRepository versionRepository,
            AgentProviderRepository providerRepository,
            TenantProvider tenantProvider,
            CurrentPrincipalProvider principalProvider,
            ObjectMapper objectMapper,
            AgentOutputSchemaValidator outputSchemaValidator
    ) {
        this.definitionRepository = definitionRepository;
        this.versionRepository = versionRepository;
        this.providerRepository = providerRepository;
        this.tenantProvider = tenantProvider;
        this.principalProvider = principalProvider;
        this.objectMapper = objectMapper;
        this.outputSchemaValidator = outputSchemaValidator;
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
                principalProvider, objectMapper, new AgentOutputSchemaValidator(objectMapper));
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
                command.providerId(), normalizeNullable(command.modelName()),
                command.systemPrompt() == null ? "" : command.systemPrompt().trim(), command.timeoutSeconds(),
                command.failurePolicy(), normalizeNullable(command.inputSchema()),
                normalizeNullable(command.outputSchema()), existing.createdBy(),
                null, null, existing.createdAt(), existing.updatedAt());
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
                null);
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
        } catch (ModelProviderException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Output Schema must be valid JSON", exception);
        }
    }

    private void validateInputSchema(String inputSchema) {
        if (!StringUtils.hasText(inputSchema)) {
            return;
        }
        try {
            outputSchemaValidator.validateDefinition(inputSchema);
        } catch (ModelProviderException exception) {
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
                version.id(), version.definitionId(), version.version(), version.status(), version.providerId(),
                providerName, version.modelName(), version.systemPrompt(), version.timeoutSeconds(),
                version.failurePolicy(), version.inputSchema(), version.outputSchema(), version.createdBy(),
                version.publishedBy(),
                version.publishedAt(), version.createdAt(), version.updatedAt());
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

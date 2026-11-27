package io.github.illuseahashmap.agent.provider.application.impl;

import io.github.illuseahashmap.agent.provider.application.AgentProviderService;
import io.github.illuseahashmap.agent.provider.application.dto.AgentProviderCommand;
import io.github.illuseahashmap.agent.provider.application.dto.AgentProviderView;
import io.github.illuseahashmap.agent.provider.application.port.AgentCredentialCipher;
import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderRepository;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.net.URI;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AgentProviderServiceImpl implements AgentProviderService {

    private final AgentProviderRepository repository;
    private final AgentCredentialCipher credentialCipher;
    private final TenantProvider tenantProvider;

    public AgentProviderServiceImpl(
            AgentProviderRepository repository,
            AgentCredentialCipher credentialCipher,
            TenantProvider tenantProvider
    ) {
        this.repository = repository;
        this.credentialCipher = credentialCipher;
        this.tenantProvider = tenantProvider;
    }

    @Override
    public PageResult<AgentProviderView> page(
            Integer pageNum,
            Integer pageSize,
            String keyword,
            Boolean enabled
    ) {
        int normalizedPageNum = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int normalizedPageSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        PageSlice<AgentProvider> page = repository.page(new AgentProviderRepository.PageCriteria(
                normalizedPageNum, normalizedPageSize, tenantCode(), normalizeNullable(keyword), enabled));
        return new PageResult<>(page.total(), page.pageNumber(), page.pageSize(),
                page.items().stream().map(this::toView).toList());
    }

    @Override
    public List<AgentProviderView> enabled() {
        return repository.findEnabled(tenantCode()).stream().map(this::toView).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentProviderView create(AgentProviderCommand command) {
        String tenantCode = tenantCode();
        String code = command.code().trim();
        assertUniqueCode(tenantCode, code, null);
        validateConfiguration(command);
        AgentProvider saved = repository.save(toProvider(null, tenantCode, command, false, null));
        saveCredentialIfPresent(tenantCode, saved.id(), command.credential());
        return toView(requireProvider(tenantCode, saved.id()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentProviderView update(long id, AgentProviderCommand command) {
        String tenantCode = tenantCode();
        AgentProvider existing = requireProvider(tenantCode, id);
        if (!existing.code().equals(command.code().trim())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Provider code cannot be changed");
        }
        validateConfiguration(command);
        repository.update(toProvider(
                id, tenantCode, command, existing.credentialConfigured(), existing.credentialHint()));
        saveCredentialIfPresent(tenantCode, id, command.credential());
        return toView(requireProvider(tenantCode, id));
    }

    private void validateConfiguration(AgentProviderCommand command) {
        if (command.type() == AgentProviderType.MOCK) {
            return;
        }
        if (!StringUtils.hasText(command.baseUrl())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Base URL is required for OpenAI-compatible Provider");
        }
        try {
            URI uri = URI.create(command.baseUrl().trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("unsupported URL");
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Provider Base URL must be a valid HTTP URL");
        }
    }

    private AgentProvider toProvider(
            Long id,
            String tenantCode,
            AgentProviderCommand command,
            boolean credentialConfigured,
            String credentialHint
    ) {
        return new AgentProvider(
                id, tenantCode, command.code().trim(), command.name().trim(), command.type(),
                normalizeNullable(command.baseUrl()), normalizeNullable(command.defaultModel()), command.enabled(),
                credentialConfigured, credentialHint, null, null);
    }

    private void saveCredentialIfPresent(String tenantCode, long providerId, String credential) {
        if (!StringUtils.hasText(credential)) {
            return;
        }
        String normalized = credential.trim();
        String hint = normalized.length() <= 4 ? "****" : normalized.substring(normalized.length() - 4);
        repository.saveCredential(
                tenantCode, providerId, credentialCipher.encrypt(tenantCode, providerId, normalized), hint);
    }

    private AgentProvider requireProvider(String tenantCode, long id) {
        return repository.findById(tenantCode, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent Provider does not exist"));
    }

    private void assertUniqueCode(String tenantCode, String code, Long excludedId) {
        if (repository.existsByCode(tenantCode, code, excludedId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Provider code already exists in the current tenant");
        }
    }

    private AgentProviderView toView(AgentProvider provider) {
        return new AgentProviderView(
                provider.id(), provider.code(), provider.name(), provider.type(), provider.baseUrl(),
                provider.defaultModel(), provider.enabled(), provider.credentialConfigured(),
                provider.credentialHint(), provider.createdAt(), provider.updatedAt());
    }

    private String tenantCode() {
        return tenantProvider.current().tenantCode();
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}

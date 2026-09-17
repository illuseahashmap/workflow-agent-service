package io.github.illuseahashmap.agent.runtime.application.impl;

import io.github.illuseahashmap.agent.runtime.application.AgentRunOperationsService;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunOperationsRepository;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunOperationsServiceImpl implements AgentRunOperationsService {

    private final AgentRunOperationsRepository repository;
    private final TenantProvider tenantProvider;
    private final CurrentPrincipalProvider principalProvider;

    public AgentRunOperationsServiceImpl(
            AgentRunOperationsRepository repository,
            TenantProvider tenantProvider,
            CurrentPrincipalProvider principalProvider
    ) {
        this.repository = repository;
        this.tenantProvider = tenantProvider;
        this.principalProvider = principalProvider;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retryFailed(long runId, String reason, int retryWindowSeconds) {
        if (runId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent run id must be positive");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Retry reason must not be blank");
        }
        if (retryWindowSeconds < 30 || retryWindowSeconds > 3600) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Retry window must be between 30 and 3600 seconds");
        }
        boolean requeued = repository.requeueFailed(
                tenantProvider.current().tenantCode(), runId,
                principalProvider.current().principalId(), UUID.randomUUID().toString(), reason.strip(),
                retryWindowSeconds);
        if (!requeued) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Agent run is not an operator-retryable terminal run");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelActive(long runId, String reason) {
        if (runId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent run id must be positive");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Cancellation reason must not be blank");
        }
        boolean cancelled = repository.cancelActive(
                tenantProvider.current().tenantCode(), runId,
                principalProvider.current().principalId(), UUID.randomUUID().toString(), reason.strip());
        if (!cancelled) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Agent run is not an active cancellable run");
        }
    }
}

package io.github.illuseahashmap.agent.runtime.application.impl;

import io.github.illuseahashmap.agent.runtime.application.AgentRunOperationsService;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunOperationsRepository;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRuntimeMetrics;
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
    private final AgentRuntimeMetrics metrics;

    public AgentRunOperationsServiceImpl(
            AgentRunOperationsRepository repository,
            TenantProvider tenantProvider,
            CurrentPrincipalProvider principalProvider,
            AgentRuntimeMetrics metrics
    ) {
        this.repository = repository;
        this.tenantProvider = tenantProvider;
        this.principalProvider = principalProvider;
        this.metrics = metrics;
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
        metrics.cancelled();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pauseActive(long runId, String reason) {
        validateRunAndReason(runId, reason, "Pause");
        var result = repository.requestPause(
                tenantProvider.current().tenantCode(), runId,
                principalProvider.current().principalId(), UUID.randomUUID().toString(), reason.strip());
        if (result == AgentRunOperationsRepository.OperationResult.REJECTED) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Agent run is not an active pausable run");
        }
        if (result == AgentRunOperationsRepository.OperationResult.APPLIED) {
            metrics.pauseRequested();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resumePaused(long runId, String reason, int resumeWindowSeconds) {
        validateRunAndReason(runId, reason, "Resume");
        if (resumeWindowSeconds < 30 || resumeWindowSeconds > 3600) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Resume window must be between 30 and 3600 seconds");
        }
        var result = repository.resumePaused(
                tenantProvider.current().tenantCode(), runId,
                principalProvider.current().principalId(), UUID.randomUUID().toString(), reason.strip(),
                resumeWindowSeconds);
        if (result == AgentRunOperationsRepository.OperationResult.REJECTED) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Agent run is not a paused resumable run");
        }
        if (result == AgentRunOperationsRepository.OperationResult.APPLIED) {
            metrics.resumed();
        }
    }

    private void validateRunAndReason(long runId, String reason, String operation) {
        if (runId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent run id must be positive");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, operation + " reason must not be blank");
        }
    }
}

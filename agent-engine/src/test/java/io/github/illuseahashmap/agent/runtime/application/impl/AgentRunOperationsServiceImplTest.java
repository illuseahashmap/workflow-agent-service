package io.github.illuseahashmap.agent.runtime.application.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.agent.runtime.application.port.AgentRunOperationsRepository;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRuntimeMetrics;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentRunOperationsServiceImplTest {

    private final AgentRunOperationsRepository repository = mock(AgentRunOperationsRepository.class);
    private final TenantProvider tenantProvider = mock(TenantProvider.class);
    private final CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
    private final AgentRuntimeMetrics metrics = mock(AgentRuntimeMetrics.class);
    private final AgentRunOperationsServiceImpl service = new AgentRunOperationsServiceImpl(
            repository, tenantProvider, principalProvider, metrics);

    @BeforeEach
    void context() {
        when(tenantProvider.current()).thenReturn(new TenantContext.TenantInfo(
                "tenant-id", "tenant-a", "Tenant A"));
        when(principalProvider.current()).thenReturn(new CurrentPrincipal(
                "USER", "operator-1", "admin", "Admin", "tenant-a", Set.of(), Set.of()));
    }

    @Test
    void requestsPauseIdempotentlyAndEmitsMetricOnlyWhenApplied() {
        when(repository.requestPause(anyString(), org.mockito.ArgumentMatchers.eq(7L), anyString(),
                anyString(), org.mockito.ArgumentMatchers.eq("maintenance")))
                .thenReturn(AgentRunOperationsRepository.OperationResult.APPLIED);

        service.pauseActive(7L, " maintenance ");

        verify(repository).requestPause(org.mockito.ArgumentMatchers.eq("tenant-a"),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("operator-1"),
                anyString(), org.mockito.ArgumentMatchers.eq("maintenance"));
        verify(metrics).pauseRequested();
    }

    @Test
    void rejectsResumeOutsidePausedState() {
        when(repository.resumePaused(anyString(), org.mockito.ArgumentMatchers.eq(7L), anyString(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.eq(120)))
                .thenReturn(AgentRunOperationsRepository.OperationResult.REJECTED);

        assertThatThrownBy(() -> service.resumePaused(7L, "resume", 120))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Agent run is not a paused resumable run");
    }

    @Test
    void validatesResumeWindowBeforePersistence() {
        assertThatThrownBy(() -> service.resumePaused(7L, "resume", 5))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Resume window must be between 30 and 3600 seconds");
    }
}

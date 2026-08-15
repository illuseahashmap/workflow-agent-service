package io.github.illuseahashmap.workflow.process.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.process.application.port.WorkflowOperationAuditRepository;
import io.github.illuseahashmap.workflow.process.domain.WorkflowOperationAudit;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.CurrentTraceContext;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkflowOperationAuditServiceTest {

    @AfterEach
    void clearTrace() {
        CurrentTraceContext.clear();
    }

    @Test
    void recordsTrustedActorTenantAndTraceContext() {
        WorkflowOperationAuditRepository repository = mock(WorkflowOperationAuditRepository.class);
        CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
        TenantProvider tenantProvider = mock(TenantProvider.class);
        when(principalProvider.current()).thenReturn(new CurrentPrincipal(
                "USER", "user-1", "alice", "Alice", "tenant-a", Set.of(), Set.of()));
        when(tenantProvider.current()).thenReturn(new TenantContext.TenantInfo(
                "tenant-id-a", "tenant-a", "Tenant A"));
        CurrentTraceContext.set("trace-1");

        new WorkflowOperationAuditService(repository, principalProvider, tenantProvider).record(
                "TASK_APPROVED", "process-1", "leave", "task-1", "review",
                "ACTIVE", "COMPLETED", "approved");

        ArgumentCaptor<WorkflowOperationAudit> captor = ArgumentCaptor.forClass(WorkflowOperationAudit.class);
        verify(repository).record(captor.capture());
        assertThat(captor.getValue().tenantCode()).isEqualTo("tenant-a");
        assertThat(captor.getValue().actorId()).isEqualTo("user-1");
        assertThat(captor.getValue().traceId()).isEqualTo("trace-1");
        assertThat(captor.getValue().previousState()).isEqualTo("ACTIVE");
        assertThat(captor.getValue().nextState()).isEqualTo("COMPLETED");
    }
}

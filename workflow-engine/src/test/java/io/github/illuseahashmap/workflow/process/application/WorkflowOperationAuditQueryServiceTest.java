package io.github.illuseahashmap.workflow.process.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.process.application.dto.WorkflowOperationAuditView;
import io.github.illuseahashmap.workflow.process.application.port.WorkflowOperationAuditQueryRepository;
import io.github.illuseahashmap.workflow.shared.context.TenantContext.TenantInfo;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowOperationAuditQueryServiceTest {

    @Test
    void normalizesPaginationAndAlwaysUsesCurrentTenant() {
        WorkflowOperationAuditQueryRepository repository = mock(WorkflowOperationAuditQueryRepository.class);
        TenantProvider tenantProvider = () -> new TenantInfo("tenant-a", "tenant-a", "Tenant A");
        when(repository.page(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PageSlice<>(0, 1, 100, List.<WorkflowOperationAuditView>of()));

        var result = new WorkflowOperationAuditQueryService(repository, tenantProvider)
                .page(0, 1000, "  TASK_APPROVED ", "  process-1 ", " trace-1 ", null, null);

        assertThat(result.pageSize()).isEqualTo(100);
        var criteria = org.mockito.ArgumentCaptor.forClass(
                WorkflowOperationAuditQueryRepository.PageCriteria.class);
        org.mockito.Mockito.verify(repository).page(criteria.capture());
        assertThat(criteria.getValue().tenantCode()).isEqualTo("tenant-a");
        assertThat(criteria.getValue().eventType()).isEqualTo("TASK_APPROVED");
        assertThat(criteria.getValue().processInstanceId()).isEqualTo("process-1");
        assertThat(criteria.getValue().traceId()).isEqualTo("trace-1");
    }

    @Test
    void rejectsReversedTimeRange() {
        var service = new WorkflowOperationAuditQueryService(
                mock(WorkflowOperationAuditQueryRepository.class),
                () -> new TenantInfo("tenant-a", "tenant-a", "Tenant A"));

        assertThatThrownBy(() -> service.page(1, 20, null, null, null,
                java.time.Instant.parse("2026-01-02T00:00:00Z"),
                java.time.Instant.parse("2026-01-01T00:00:00Z")))
                .hasMessage("Audit time range is invalid");
    }
}

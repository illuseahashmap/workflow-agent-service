package io.github.illuseahashmap.workflow.process.application.port;

import io.github.illuseahashmap.workflow.process.application.dto.WorkflowOperationAuditView;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import java.time.Instant;

public interface WorkflowOperationAuditQueryRepository {

    PageSlice<WorkflowOperationAuditView> page(PageCriteria criteria);

    record PageCriteria(
            int pageNumber,
            int pageSize,
            String tenantCode,
            String eventType,
            String processInstanceId,
            String traceId,
            Instant occurredFrom,
            Instant occurredTo
    ) {
    }
}

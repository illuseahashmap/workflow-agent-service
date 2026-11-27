package io.github.illuseahashmap.workflow.process.application.port;

import io.github.illuseahashmap.workflow.process.domain.WorkflowOperationAudit;

@FunctionalInterface
public interface WorkflowOperationAuditRepository {

    void record(WorkflowOperationAudit audit);
}

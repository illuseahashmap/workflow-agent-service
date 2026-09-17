package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.port.WorkflowOperationAuditRepository;
import io.github.illuseahashmap.workflow.process.domain.WorkflowOperationAudit;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.CurrentTraceContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Records workflow audit evidence inside the same transaction as the state change. */
@Service
public class WorkflowOperationAuditService {

    private final WorkflowOperationAuditRepository repository;
    private final CurrentPrincipalProvider principalProvider;
    private final TenantProvider tenantProvider;

    public WorkflowOperationAuditService(
            WorkflowOperationAuditRepository repository,
            CurrentPrincipalProvider principalProvider,
            TenantProvider tenantProvider
    ) {
        this.repository = repository;
        this.principalProvider = principalProvider;
        this.tenantProvider = tenantProvider;
    }

    public void record(
            String eventType,
            String processInstanceId,
            String processDefinitionKey,
            String taskId,
            String subject,
            String previousState,
            String nextState,
            String reason
    ) {
        CurrentPrincipal actor = principalProvider.current();
        repository.record(new WorkflowOperationAudit(
                eventType,
                tenantProvider.current().tenantCode(),
                actor.principalType(),
                actor.principalId(),
                actor.username(),
                processInstanceId,
                processDefinitionKey,
                taskId,
                subject,
                previousState,
                nextState,
                normalize(reason),
                CurrentTraceContext.currentOrNull(),
                Instant.now()));
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }
}

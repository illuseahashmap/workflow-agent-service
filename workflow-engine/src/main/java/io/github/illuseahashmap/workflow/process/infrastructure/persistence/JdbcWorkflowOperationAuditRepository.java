package io.github.illuseahashmap.workflow.process.infrastructure.persistence;

import io.github.illuseahashmap.workflow.process.application.port.WorkflowOperationAuditRepository;
import io.github.illuseahashmap.workflow.process.domain.WorkflowOperationAudit;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkflowOperationAuditRepository implements WorkflowOperationAuditRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcWorkflowOperationAuditRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(WorkflowOperationAudit audit) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("eventType", audit.eventType());
        parameters.put("tenantCode", audit.tenantCode());
        parameters.put("actorType", audit.actorType());
        parameters.put("actorId", audit.actorId());
        parameters.put("actorUsername", audit.actorUsername());
        parameters.put("processInstanceId", audit.processInstanceId());
        parameters.put("processDefinitionKey", audit.processDefinitionKey());
        parameters.put("taskId", audit.taskId());
        parameters.put("subject", audit.subject());
        parameters.put("previousState", audit.previousState());
        parameters.put("nextState", audit.nextState());
        parameters.put("reason", audit.reason());
        parameters.put("traceId", audit.traceId());
        parameters.put("occurredAt", Timestamp.from(audit.occurredAt()));
        jdbcTemplate.update("""
                INSERT INTO workflow_operation_audit (
                    event_type, tenant_code, actor_type, actor_id, actor_username,
                    process_instance_id, process_definition_key, task_id,
                    subject, previous_state, next_state, reason, trace_id, occurred_at
                ) VALUES (
                    :eventType, :tenantCode, :actorType, :actorId, :actorUsername,
                    :processInstanceId, :processDefinitionKey, :taskId,
                    :subject, :previousState, :nextState, :reason, :traceId, :occurredAt
                )
                """, parameters);
    }
}

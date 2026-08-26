package io.github.illuseahashmap.workflow.process.infrastructure.persistence;

import io.github.illuseahashmap.workflow.process.application.dto.WorkflowOperationAuditView;
import io.github.illuseahashmap.workflow.process.application.port.WorkflowOperationAuditQueryRepository;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkflowOperationAuditQueryRepository implements WorkflowOperationAuditQueryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcWorkflowOperationAuditQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PageSlice<WorkflowOperationAuditView> page(PageCriteria criteria) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantCode", criteria.tenantCode());
        parameters.put("eventType", criteria.eventType());
        parameters.put("processInstanceId", criteria.processInstanceId());
        parameters.put("traceId", criteria.traceId());
        parameters.put("occurredFrom", criteria.occurredFrom());
        parameters.put("occurredTo", criteria.occurredTo());
        parameters.put("limit", criteria.pageSize());
        parameters.put("offset", (criteria.pageNumber() - 1) * criteria.pageSize());
        String filter = """
                tenant_code = :tenantCode
                AND (:eventType IS NULL OR event_type = :eventType)
                AND (:processInstanceId IS NULL OR process_instance_id = :processInstanceId)
                AND (:traceId IS NULL OR trace_id = :traceId)
                AND (:occurredFrom IS NULL OR occurred_at >= :occurredFrom)
                AND (:occurredTo IS NULL OR occurred_at <= :occurredTo)
                """;
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_operation_audit WHERE " + filter, parameters, Long.class);
        var items = jdbcTemplate.query("""
                SELECT id, event_type, tenant_code, actor_type, actor_id, actor_username,
                       process_instance_id, process_definition_key, task_id, subject,
                       previous_state, next_state, reason, trace_id, occurred_at
                FROM workflow_operation_audit
                WHERE """ + filter + """
                ORDER BY occurred_at DESC, id DESC
                LIMIT :limit OFFSET :offset
                """, parameters, (resultSet, rowNum) -> map(resultSet));
        return new PageSlice<>(total, criteria.pageNumber(), criteria.pageSize(), items);
    }

    private WorkflowOperationAuditView map(ResultSet resultSet) throws SQLException {
        return new WorkflowOperationAuditView(
                resultSet.getLong("id"), resultSet.getString("event_type"),
                resultSet.getString("tenant_code"), resultSet.getString("actor_type"),
                resultSet.getString("actor_id"), resultSet.getString("actor_username"),
                resultSet.getString("process_instance_id"), resultSet.getString("process_definition_key"),
                resultSet.getString("task_id"), resultSet.getString("subject"),
                resultSet.getString("previous_state"), resultSet.getString("next_state"),
                resultSet.getString("reason"), resultSet.getString("trace_id"),
                resultSet.getObject("occurred_at", OffsetDateTime.class));
    }
}

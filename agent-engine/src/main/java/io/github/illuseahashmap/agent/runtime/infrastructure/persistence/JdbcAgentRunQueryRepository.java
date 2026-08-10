package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunAttemptView;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunCheckpointView;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunDetailView;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunPayloadView;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunStateHistoryView;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunStepView;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunView;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentModelInvocationView;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunQueryRepository;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunOperatorType;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStatus;
import io.github.illuseahashmap.agent.runtime.domain.AttemptStatus;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import io.github.illuseahashmap.agent.runtime.domain.StepStatus;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentRunQueryRepository implements AgentRunQueryRepository {

    private static final String FROM_CLAUSE = """
            FROM agent_run r
            JOIN agent_definition_version v ON v.id = r.agent_version_id AND v.tenant_code = r.tenant_code
            JOIN agent_definition d ON d.id = v.definition_id AND d.tenant_code = r.tenant_code
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcAgentRunQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PageSlice<AgentRunView> page(PageCriteria criteria) {
        StringBuilder where = new StringBuilder(" WHERE r.tenant_code = :tenantCode");
        var parameters = new HashMap<String, Object>();
        parameters.put("tenantCode", criteria.tenantCode());
        if (criteria.keyword() != null) {
            where.append(" AND (LOWER(d.agent_code) LIKE :keyword OR LOWER(d.agent_name) LIKE :keyword"
                    + " OR LOWER(COALESCE(r.process_instance_id, '')) LIKE :keyword)");
            parameters.put("keyword", "%" + criteria.keyword().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (criteria.status() != null) {
            where.append(" AND r.status = :status");
            parameters.put("status", criteria.status().name());
        }
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " + FROM_CLAUSE + where, parameters, Long.class);
        parameters.put("limit", criteria.pageSize());
        parameters.put("offset", (criteria.pageNum() - 1) * criteria.pageSize());
        List<AgentRunView> items = jdbcTemplate.query("""
                        SELECT r.*, d.agent_code, d.agent_name, v.version AS agent_version
                        """ + FROM_CLAUSE + where
                        + " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit OFFSET :offset",
                parameters,
                (resultSet, rowNum) -> mapView(resultSet));
        return new PageSlice<>(total, criteria.pageNum(), criteria.pageSize(), items);
    }

    @Override
    public Optional<AgentRunDetailView> findDetail(String tenantCode, long runId) {
        Map<String, Object> parameters = Map.of("tenantCode", tenantCode, "runId", runId);
        List<AgentRunView> runs = jdbcTemplate.query("""
                        SELECT r.*, d.agent_code, d.agent_name, v.version AS agent_version
                        """ + FROM_CLAUSE + " WHERE r.tenant_code = :tenantCode AND r.id = :runId",
                parameters,
                (resultSet, rowNum) -> mapView(resultSet));
        if (runs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AgentRunDetailView(
                runs.getFirst(),
                findPayload(parameters),
                findAttempts(parameters),
                findSteps(parameters),
                findModelInvocations(parameters),
                findCheckpoints(parameters),
                findStateHistory(parameters)));
    }

    private AgentRunPayloadView findPayload(Map<String, Object> parameters) {
        return jdbcTemplate.queryForObject("""
                        SELECT input_snapshot_json::text AS input_snapshot_json,
                               output_snapshot_json::text AS output_snapshot_json
                        FROM agent_run
                        WHERE tenant_code = :tenantCode AND id = :runId
                        """,
                parameters,
                (resultSet, rowNum) -> new AgentRunPayloadView(
                        resultSet.getString("input_snapshot_json"),
                        resultSet.getString("output_snapshot_json")));
    }

    private List<AgentRunAttemptView> findAttempts(Map<String, Object> parameters) {
        return jdbcTemplate.query("""
                        SELECT * FROM agent_run_attempt
                        WHERE tenant_code = :tenantCode AND agent_run_id = :runId
                        ORDER BY attempt_no, id
                        """,
                parameters,
                (resultSet, rowNum) -> new AgentRunAttemptView(
                        resultSet.getLong("id"),
                        resultSet.getInt("attempt_no"),
                        AttemptStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("error_code"),
                        offsetDateTime(resultSet, "started_at"),
                        offsetDateTime(resultSet, "completed_at"),
                        offsetDateTime(resultSet, "created_at"),
                        offsetDateTime(resultSet, "updated_at")));
    }

    private List<AgentRunStepView> findSteps(Map<String, Object> parameters) {
        return jdbcTemplate.query("""
                        SELECT * FROM agent_run_step
                        WHERE tenant_code = :tenantCode AND agent_run_id = :runId
                        ORDER BY attempt_id, sequence_no, id
                        """,
                parameters,
                (resultSet, rowNum) -> new AgentRunStepView(
                        resultSet.getLong("id"),
                        resultSet.getLong("attempt_id"),
                        resultSet.getInt("sequence_no"),
                        resultSet.getString("step_type"),
                        StepStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("error_code"),
                        offsetDateTime(resultSet, "started_at"),
                        offsetDateTime(resultSet, "completed_at"),
                        offsetDateTime(resultSet, "created_at"),
                        offsetDateTime(resultSet, "updated_at")));
    }

    private List<AgentRunCheckpointView> findCheckpoints(Map<String, Object> parameters) {
        return jdbcTemplate.query("""
                        SELECT * FROM agent_run_checkpoint
                        WHERE tenant_code = :tenantCode AND agent_run_id = :runId
                        ORDER BY attempt_id, sequence_no, id
                        """,
                parameters,
                (resultSet, rowNum) -> new AgentRunCheckpointView(
                        resultSet.getLong("id"),
                        resultSet.getLong("attempt_id"),
                        resultSet.getInt("sequence_no"),
                        resultSet.getString("checkpoint_type"),
                        resultSet.getString("snapshot_json"),
                        offsetDateTime(resultSet, "created_at")));
    }

    private List<AgentModelInvocationView> findModelInvocations(Map<String, Object> parameters) {
        return jdbcTemplate.query("""
                        SELECT invocation.*, provider.provider_name
                        FROM agent_model_invocation invocation
                        JOIN agent_provider provider
                          ON provider.id = invocation.provider_id
                         AND provider.tenant_code = invocation.tenant_code
                        WHERE invocation.tenant_code = :tenantCode
                          AND invocation.agent_run_id = :runId
                        ORDER BY invocation.attempt_id, invocation.id
                        """,
                parameters,
                (resultSet, rowNum) -> new AgentModelInvocationView(
                        resultSet.getLong("id"),
                        resultSet.getLong("attempt_id"),
                        resultSet.getLong("step_id"),
                        resultSet.getString("provider_name"),
                        resultSet.getString("requested_model"),
                        resultSet.getString("actual_model"),
                        resultSet.getString("provider_request_id"),
                        resultSet.getString("finish_reason"),
                        resultSet.getString("status"),
                        resultSet.getInt("input_tokens"),
                        resultSet.getInt("output_tokens"),
                        resultSet.getInt("reasoning_tokens"),
                        resultSet.getObject("latency_ms", Long.class),
                        resultSet.getString("error_code"),
                        offsetDateTime(resultSet, "created_at"),
                        offsetDateTime(resultSet, "completed_at")));
    }

    private List<AgentRunStateHistoryView> findStateHistory(Map<String, Object> parameters) {
        return jdbcTemplate.query("""
                        SELECT * FROM agent_run_state_history
                        WHERE tenant_code = :tenantCode AND agent_run_id = :runId
                        ORDER BY created_at, id
                        """,
                parameters,
                (resultSet, rowNum) -> new AgentRunStateHistoryView(
                        resultSet.getLong("id"),
                        resultSet.getObject("attempt_id", Long.class),
                        AgentRunStatus.valueOf(resultSet.getString("old_status")),
                        AgentRunStatus.valueOf(resultSet.getString("new_status")),
                        resultSet.getString("reason_code"),
                        AgentRunOperatorType.valueOf(resultSet.getString("operator_type")),
                        resultSet.getString("operator_id"),
                        resultSet.getString("trace_id"),
                        offsetDateTime(resultSet, "created_at")));
    }

    private AgentRunView mapView(ResultSet resultSet) throws SQLException {
        String resultStatus = resultSet.getString("result_status");
        return new AgentRunView(
                resultSet.getLong("id"),
                resultSet.getString("agent_code"),
                resultSet.getString("agent_name"),
                resultSet.getInt("agent_version"),
                AgentRunStatus.valueOf(resultSet.getString("status")),
                resultStatus == null ? null : ResultStatus.valueOf(resultStatus),
                resultSet.getString("process_instance_id"),
                resultSet.getString("activity_id"),
                resultSet.getString("error_code"),
                resultSet.getObject("deadline_at", java.time.OffsetDateTime.class),
                resultSet.getObject("started_at", java.time.OffsetDateTime.class),
                resultSet.getObject("completed_at", java.time.OffsetDateTime.class),
                resultSet.getObject("created_at", java.time.OffsetDateTime.class),
                resultSet.getObject("updated_at", java.time.OffsetDateTime.class));
    }

    private java.time.OffsetDateTime offsetDateTime(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, java.time.OffsetDateTime.class);
    }
}

package io.github.illuseahashmap.workflow.agent;

import io.github.illuseahashmap.workflow.process.application.port.AgentCompletionRunPort;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Composition-root bridge exposing a stable Agent run snapshot to workflow-engine. */
@Component
public class JdbcAgentCompletionRunAdapter implements AgentCompletionRunPort {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcAgentCompletionRunAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CompletedAgentRun> lockCompletedRun(String tenantCode, long runId) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_code, process_instance_id, execution_id, activity_id,
                               activity_activation_id, current_attempt_id, status, error_code,
                               output_snapshot_json, output_mapping_json, process_failure_policy
                        FROM agent_run
                        WHERE id = :runId AND tenant_code = :tenantCode
                          AND trigger_type = 'FLOWABLE'
                          AND status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                        FOR UPDATE
                        """, Map.of("runId", runId, "tenantCode", tenantCode),
                (rs, rowNum) -> new CompletedAgentRun(
                        rs.getLong("id"), rs.getString("tenant_code"),
                        rs.getString("process_instance_id"), rs.getString("execution_id"),
                        rs.getString("activity_id"), rs.getString("activity_activation_id"),
                        rs.getObject("current_attempt_id", Long.class), rs.getString("status"),
                        rs.getString("error_code"), rs.getString("output_snapshot_json"),
                        rs.getString("output_mapping_json"), rs.getString("process_failure_policy")))
                .stream().findFirst();
    }

    @Override
    public void markWorkflowHandled(String tenantCode, long runId) {
        var handledAt = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                UPDATE agent_run SET workflow_resumed_at = :handledAt, updated_at = :handledAt
                WHERE id = :runId AND tenant_code = :tenantCode AND workflow_resumed_at IS NULL
                """, Map.of("runId", runId, "tenantCode", tenantCode, "handledAt", handledAt));
    }
}

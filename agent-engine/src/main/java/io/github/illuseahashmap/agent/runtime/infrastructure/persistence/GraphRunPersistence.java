package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import io.github.illuseahashmap.agent.runtime.domain.AgentRun;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStatus;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStateTransition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Shared mapping only; callers own the Run row lock and transaction. */
final class GraphRunPersistence {
    static final String IDENTITY = """
            jsonb_build_array(r.agent_version_id,r.requested_by,r.input_snapshot_json,r.process_instance_id,
                r.execution_id,r.activity_id,r.activity_activation_id,r.output_mapping_json,r.process_failure_policy,
                r.trigger_type,extract(epoch from r.deadline_at))
            """;
    private GraphRunPersistence() { }
    static AgentRun read(ResultSet rs) throws SQLException {
        return AgentRun.restore(rs.getLong("id"), rs.getString("tenant_code"), rs.getLong("agent_version_id"),
                rs.getString("idempotency_key"), AgentRunStatus.valueOf(rs.getString("status")), rs.getObject("current_attempt_id", Long.class),
                rs.getString("lease_owner"), instant(rs, "lease_expires_at"), instant(rs, "deadline_at"), instant(rs, "started_at"),
                instant(rs, "completed_at"), instant(rs, "created_at"), instant(rs, "updated_at"), rs.getString("process_instance_id"),
                rs.getString("execution_id"), rs.getString("activity_id"), rs.getString("activity_activation_id"));
    }
    static Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getObject(name, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    static OffsetDateTime timestamp(Instant now) { return OffsetDateTime.ofInstant(now, ZoneOffset.UTC); }
    static void history(NamedParameterJdbcTemplate jdbc, AgentRunStateTransition t) {
        jdbc.update("""
                INSERT INTO agent_run_state_history(tenant_code,agent_run_id,attempt_id,old_status,new_status,
                    reason_code,operator_type,operator_id,trace_id,created_at)
                VALUES(:tenant,:run,:attempt,:old,:next,:reason,:type,:operator,:trace,:now)
                """, new MapSqlParameterSource("tenant", t.tenantCode()).addValue("run", t.agentRunId()).addValue("attempt", t.attemptId())
                .addValue("old", t.oldStatus().name()).addValue("next", t.newStatus().name()).addValue("reason", t.reasonCode())
                .addValue("type", t.operatorType().name()).addValue("operator", t.operatorId()).addValue("trace", t.traceId())
                .addValue("now", timestamp(t.createdAt())));
    }
}

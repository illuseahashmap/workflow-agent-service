package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import io.github.illuseahashmap.agent.runtime.application.port.AgentRunOperationsRepository.OperationResult;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Map;

/** Operator commands preserve the graph's frozen deadline, identity and single business attempt. */
final class JdbcGraphRunOperations {
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcAgentRunOperationsRepository audit;

    JdbcGraphRunOperations(
            NamedParameterJdbcTemplate jdbc, JdbcAgentRunOperationsRepository audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    boolean handles(String tenant, long run) {
        return !jdbc.query(
                        "SELECT id FROM agent_run WHERE tenant_code=:tenant AND id=:run AND"
                                + " execution_backend='LANGGRAPH' FOR UPDATE",
                        Map.of("tenant", tenant, "run", run),
                        (rs, n) -> rs.getLong(1))
                .isEmpty();
    }

    boolean cancel(String tenant, long run, String actor, String trace, String reason) {
        var key = Map.of("tenant", tenant, "run", run);
        var states =
                jdbc.query(
                        "SELECT status FROM agent_run WHERE tenant_code=:tenant AND id=:run AND"
                                + " status IN ('QUEUED','RUNNING','PAUSED')",
                        key,
                        (rs, n) -> rs.getString(1));
        if (states.isEmpty()) {
            return false;
        }
        int changed =
                jdbc.update(
                        "INSERT INTO agent_graph_run_stop(tenant_code,run_id,reason)"
                            + " VALUES(:tenant,:run,'OPERATOR_CANCELLED') ON CONFLICT DO NOTHING",
                        key);
        if (changed == 1) {
            audit.recordOperation(
                    tenant,
                    run,
                    "CANCEL",
                    states.getFirst(),
                    states.getFirst(),
                    actor,
                    trace,
                    reason);
        }
        return true;
    }

    OperationResult pause(String tenant, long run, String actor, String trace, String reason) {
        var key = Map.of("tenant", tenant, "run", run);
        var rows =
                jdbc.query(
                        "SELECT status,current_attempt_id FROM agent_run WHERE tenant_code=:tenant"
                            + " AND id=:run AND deadline_at>clock_timestamp() AND NOT EXISTS(SELECT"
                            + " 1 FROM agent_graph_run_stop WHERE tenant_code=:tenant AND"
                            + " run_id=:run)",
                        key,
                        (rs, n) -> new State(rs.getString(1), rs.getObject(2, Long.class)));
        if (rows.isEmpty()) {
            return OperationResult.REJECTED;
        }
        var state = rows.getFirst();
        if ("PAUSED".equals(state.status())) {
            return OperationResult.ALREADY_APPLIED;
        }
        if (!java.util.Set.of("QUEUED", "RUNNING").contains(state.status())) {
            return OperationResult.REJECTED;
        }
        jdbc.update(
                "UPDATE agent_run SET"
                    + " status='PAUSED',paused_at=clock_timestamp(),pause_requested_at=NULL,updated_at=clock_timestamp()"
                    + " WHERE tenant_code=:tenant AND id=:run",
                key);
        jdbc.update(
                "UPDATE agent_graph_execution SET"
                        + " capability_hash=NULL,lease_owner=NULL,lease_expires_at=NULL"
                        + " WHERE tenant_code=:tenant AND run_id=:run",
                key);
        // ACK is durable: pause must not turn an acknowledged terminal observation into execution.
        jdbc.update(
                "UPDATE agent_graph_dispatch SET status='READY' WHERE tenant_code=:tenant AND"
                        + " run_id=:run AND status='RUNNING'",
                key);
        audit.insertStateHistory(
                Map.of("tenantCode", tenant, "runId", run, "operatorId", actor, "traceId", trace),
                state.attempt(),
                state.status(),
                "PAUSED",
                "OPERATOR_PAUSED");
        audit.recordOperation(tenant, run, "PAUSE", state.status(), "PAUSED", actor, trace, reason);
        return OperationResult.APPLIED;
    }

    OperationResult resume(String tenant, long run, String actor, String trace, String reason) {
        var key = Map.of("tenant", tenant, "run", run);
        var rows =
                jdbc.query(
                        "SELECT status,current_attempt_id FROM agent_run WHERE tenant_code=:tenant"
                            + " AND id=:run AND deadline_at>clock_timestamp() AND NOT EXISTS(SELECT"
                            + " 1 FROM agent_graph_run_stop WHERE tenant_code=:tenant AND"
                            + " run_id=:run)",
                        key,
                        (rs, n) -> new State(rs.getString(1), rs.getObject(2, Long.class)));
        if (rows.isEmpty() || !"PAUSED".equals(rows.getFirst().status())) {
            return OperationResult.REJECTED;
        }
        var state = rows.getFirst();
        String next = state.attempt() == null ? "QUEUED" : "RUNNING";
        jdbc.update(
                "UPDATE agent_run SET"
                    + " status=:status,pause_requested_at=NULL,updated_at=clock_timestamp() WHERE"
                    + " tenant_code=:tenant AND id=:run",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(key)
                        .addValue("status", next));
        audit.insertStateHistory(
                Map.of("tenantCode", tenant, "runId", run, "operatorId", actor, "traceId", trace),
                state.attempt(),
                "PAUSED",
                next,
                "OPERATOR_RESUMED");
        audit.recordOperation(tenant, run, "RESUME", "PAUSED", next, actor, trace, reason);
        return OperationResult.APPLIED;
    }

    private record State(String status, Long attempt) {}
}

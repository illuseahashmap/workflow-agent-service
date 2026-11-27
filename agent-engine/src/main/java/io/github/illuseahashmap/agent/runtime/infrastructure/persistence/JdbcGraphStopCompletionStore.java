package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import io.github.illuseahashmap.agent.runtime.application.port.AgentRunEventPublisher;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunOperatorType;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStateMachine;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunTransitionContext;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Independent of graph ACK, checkpoint locks, model availability and call permits. */
final class JdbcGraphStopCompletionStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    JdbcGraphStopCompletionStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        transaction =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource())));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
    }

    boolean completeNext(AgentRunEventPublisher publisher) {
        // Commit the irreversible verdict separately so an Outbox failure cannot re-enable
        // execution.
        transaction.executeWithoutResult(
                tx ->
                        jdbc.update(
                                """
                                INSERT INTO agent_graph_run_stop(tenant_code,run_id,reason)
                                SELECT r.tenant_code,r.id,'AGENT_DEADLINE_EXCEEDED' FROM agent_run r
                                JOIN agent_graph_run_budget b ON b.tenant_code=r.tenant_code AND b.run_id=r.id
                                WHERE r.execution_backend='LANGGRAPH' AND r.status IN ('QUEUED','RUNNING','PAUSED')
                                  AND r.deadline_at<=clock_timestamp()
                                  AND NOT EXISTS(SELECT 1 FROM agent_graph_run_stop s WHERE s.tenant_code=r.tenant_code AND s.run_id=r.id)
                                ORDER BY r.deadline_at,r.id LIMIT 32 FOR UPDATE OF r SKIP LOCKED
                                ON CONFLICT DO NOTHING
                                """,
                                Map.of()));
        var selected = new java.util.concurrent.atomic.AtomicReference<MapSqlParameterSource>();
        try {
            return Boolean.TRUE.equals(
                    transaction.execute(
                            tx -> {
                                var rows =
                                        jdbc.query(
                                                """
                                                SELECT r.* FROM agent_run r JOIN agent_graph_run_stop s ON s.tenant_code=r.tenant_code AND s.run_id=r.id
                                                JOIN agent_graph_execution e ON e.tenant_code=r.tenant_code AND e.run_id=r.id
                                                WHERE NOT EXISTS(SELECT 1 FROM agent_graph_stop_completion c WHERE c.tenant_code=r.tenant_code AND c.run_id=r.id)
                                                  AND (e.stop_retry_at IS NULL OR e.stop_retry_at<=clock_timestamp())
                                                ORDER BY s.stopped_at,r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED
                                                """,
                                                Map.of(),
                                                (rs, n) -> GraphRunPersistence.read(rs));
                                if (rows.isEmpty()) {
                                    return false;
                                }
                                var run = rows.getFirst();
                                var key =
                                        new MapSqlParameterSource("tenant", run.tenantCode())
                                                .addValue("run", run.id());
                                selected.set(key);
                                // Recheck after the Run lock; both success and stop finalizers
                                // serialize on this row.
                                if (jdbc.queryForObject(
                                                "SELECT count(*) FROM agent_graph_stop_completion"
                                                    + " WHERE tenant_code=:tenant AND run_id=:run",
                                                key,
                                                Integer.class)
                                        != 0) {
                                    return false;
                                }
                                if (run.status().isTerminal()) {
                                    mark(key, "DISCARDED");
                                    return true;
                                }
                                boolean valid =
                                        Boolean.TRUE.equals(
                                                jdbc.queryForObject(
                                                        """
                                                        SELECT r.execution_backend='LANGGRAPH' AND e.business_identity=
                                                        """
                                                                + GraphRunPersistence.IDENTITY
                                                                + """
                                                                AND e.attempt_id IS NOT DISTINCT FROM r.current_attempt_id
                                                                FROM agent_run r JOIN agent_graph_execution e ON e.tenant_code=r.tenant_code AND e.run_id=r.id
                                                                WHERE r.tenant_code=:tenant AND r.id=:run
                                                                """,
                                                        key,
                                                        Boolean.class));
                                if (!valid) {
                                    mark(key, "BLOCKED");
                                    return true;
                                }
                                String reason =
                                        jdbc.queryForObject(
                                                "SELECT reason FROM agent_graph_run_stop WHERE"
                                                    + " tenant_code=:tenant AND run_id=:run",
                                                key,
                                                String.class);
                                var now =
                                        jdbc.queryForObject(
                                                        "SELECT clock_timestamp()",
                                                        Map.of(),
                                                        OffsetDateTime.class)
                                                .toInstant();
                                var context =
                                        new AgentRunTransitionContext(
                                                run.currentAttemptId(),
                                                reason,
                                                AgentRunOperatorType.SYSTEM,
                                                "graph-stop-completion",
                                                UUID.randomUUID().toString(),
                                                now);
                                new AgentRunStateMachine()
                                        .stopDelegated(
                                                run,
                                                "AGENT_DEADLINE_EXCEEDED".equals(reason),
                                                context);
                                key.addValue("status", run.status().name())
                                        .addValue("reason", reason)
                                        .addValue("now", GraphRunPersistence.timestamp(now))
                                        .addValue("attempt", run.currentAttemptId());
                                jdbc.update(
                                        """
                                        UPDATE agent_run SET status=:status,result_status='FAILED',error_code=:reason,output_snapshot_json=NULL,
                                            completed_at=:now,updated_at=:now,lease_owner=NULL,lease_expires_at=NULL,pause_requested_at=NULL
                                        WHERE tenant_code=:tenant AND id=:run
                                        """,
                                        key);
                                jdbc.update(
                                        """
                                        UPDATE agent_run_attempt SET status=:status,error_code=:reason,completed_at=:now,updated_at=:now
                                        WHERE tenant_code=:tenant AND agent_run_id=:run AND id=:attempt
                                        """,
                                        key);
                                GraphRunPersistence.history(jdbc, run.stateHistory().getLast());
                                publisher.completed(run, null);
                                mark(key, "COMMITTED");
                                // Unknown call permits remain quarantined/active for
                                // reconciliation, never blindly released.
                                return true;
                            }));
        } catch (RuntimeException failure) {
            if (selected.get() != null) {
                try {
                    transaction.executeWithoutResult(
                            tx ->
                                    jdbc.update(
                                            """
                                            UPDATE agent_graph_execution SET stop_failures=LEAST(stop_failures+1,100000),
                                                stop_retry_at=clock_timestamp()+LEAST(60,power(2,LEAST(stop_failures,6)))*interval '1 second'
                                            WHERE tenant_code=:tenant AND run_id=:run
                                            """,
                                            selected.get()));
                } catch (RuntimeException unavailable) {
                    failure.addSuppressed(unavailable);
                }
            }
            throw failure;
        }
    }

    private void mark(MapSqlParameterSource key, String outcome) {
        jdbc.update(
                "INSERT INTO agent_graph_stop_completion(tenant_code,run_id,outcome)"
                    + " VALUES(:tenant,:run,:outcome)",
                key.addValue("outcome", outcome));
    }
}

package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunEventPublisher;
import io.github.illuseahashmap.agent.runtime.application.port.GraphCompletionPort;
import io.github.illuseahashmap.agent.runtime.application.port.GraphDispatchPort.Observation;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailureDisposition;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunOperatorType;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStateMachine;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunTransitionContext;
import io.github.illuseahashmap.agent.runtime.domain.AgentTimeoutType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Run lock -> immutable evidence -> deterministic gate -> terminal Run + Outbox + acceptance marker, one commit. */
public final class JdbcGraphCompletionStore implements GraphCompletionPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcGraphExecutionStore executions;
    private final JdbcGraphCognitionStore cognition;
    private final GraphGatewayCodec codec;
    private final TransactionTemplate transaction;

    public JdbcGraphCompletionStore(NamedParameterJdbcTemplate jdbc, JdbcGraphExecutionStore executions, GraphGatewayCodec codec) {
        executions.requireSameDataSource(jdbc);
        this.jdbc = jdbc;
        this.executions = executions;
        this.codec = codec;
        this.cognition = new JdbcGraphCognitionStore(jdbc, executions, codec);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource())));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
    }

    @Override public boolean completeNext(Function<Evidence, Outcome> validator, AgentRunEventPublisher publisher) {
        var selected = new java.util.concurrent.atomic.AtomicReference<MapSqlParameterSource>();
        try {
            return Boolean.TRUE.equals(transaction.execute(tx -> {
                var pending = jdbc.query("""
                        SELECT r.* FROM agent_run r JOIN agent_graph_dispatch d ON d.tenant_code=r.tenant_code AND d.run_id=r.id
                        WHERE d.status='ACKNOWLEDGED' AND d.completion_status IS NULL AND d.available_at<=clock_timestamp()
                            AND r.status<>'PAUSED' AND r.pause_requested_at IS NULL
                        ORDER BY d.available_at,r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED
                        """, Map.of(), (rs, n) -> GraphRunPersistence.read(rs));
                if (pending.isEmpty()) { return false; }
                var run = pending.getFirst();
                var key = new MapSqlParameterSource("tenant", run.tenantCode()).addValue("run", run.id());
                selected.set(key);
                // Recheck after acquiring the lock: another transaction may have committed since the scan began.
                var rows = jdbc.query("""
                        SELECT d.observation_json, d.command_id, e.*,
                            r.agent_version_id,r.deadline_at,r.execution_backend,r.current_attempt_id,
                        """ + GraphRunPersistence.IDENTITY + """
                            =e.business_identity AS identity_matches
                        FROM agent_graph_dispatch d JOIN agent_graph_execution e USING(tenant_code,run_id)
                        JOIN agent_run r ON r.tenant_code=e.tenant_code AND r.id=e.run_id
                        WHERE d.tenant_code=:tenant AND d.run_id=:run AND d.status='ACKNOWLEDGED' AND d.completion_status IS NULL
                        """, key, (rs, n) -> {
                            boolean valid = rs.getBoolean("identity_matches") && "LANGGRAPH".equals(rs.getString("execution_backend"))
                                    && "ACTIVE".equals(rs.getString("state")) && rs.getObject("attempt_id") != null
                                    && Objects.equals(rs.getObject("attempt_id", Long.class), rs.getObject("current_attempt_id", Long.class));
                            try { return new Pending(valid, rs.getString("observation_json"), valid ? executions.authority(rs) : null); }
                            catch (GraphGatewayException mismatch) { return new Pending(false, rs.getString("observation_json"), null); }
                        });
                if (rows.isEmpty()) { return false; }
                var pendingResult = rows.getFirst();
                if (run.status().isTerminal()) {
                    mark(key, "DISCARDED", "GRAPH_RUN_ALREADY_TERMINAL");
                    return true;
                }
                if (!pendingResult.valid()) {
                    mark(key, "BLOCKED", "GRAPH_BUSINESS_BINDING_MISMATCH");
                    return true;
                }
                if (run.status() != io.github.illuseahashmap.agent.runtime.domain.AgentRunStatus.RUNNING) { return false; }
                Outcome outcome;
                try {
                    var observation = codec.read(pendingResult.observation().getBytes(StandardCharsets.UTF_8), Observation.class);
                    outcome = validator.apply(cognition.completionEvidence(pendingResult.run(), observation));
                } catch (GraphGatewayException invalid) {
                    mark(key, "BLOCKED", "GRAPH_RECEIPT_INVALID");
                    return true;
                }
                // Deadline is checked after local validation, not at the start of a potentially blocked SQL call.
                Instant now = jdbc.queryForObject("SELECT clock_timestamp()", Map.of(), OffsetDateTime.class).toInstant();
                boolean expired = !now.isBefore(run.deadlineAt());
                String reason = expired ? "AGENT_DEADLINE_EXCEEDED" : outcome.reasonCode();
                var context = new AgentRunTransitionContext(run.currentAttemptId(), reason, AgentRunOperatorType.SYSTEM,
                        "graph-completion", UUID.randomUUID().toString(), now);
                var machine = new AgentRunStateMachine();
                if (expired) { machine.markTimedOut(run, AgentTimeoutType.DEADLINE_EXCEEDED, context); }
                else if (outcome.accepted()) { machine.markSucceeded(run, true, true, context); }
                else { machine.markFailed(run, AgentFailureDisposition.NON_RETRYABLE, context); }
                String output = !expired && outcome.accepted() ? outcome.outputSnapshot() : null;
                key.addValue("attempt", run.currentAttemptId()).addValue("status", run.status().name())
                        .addValue("result", expired ? "FAILED" : outcome.resultStatus()).addValue("error", output == null ? reason : null)
                        .addValue("output", output).addValue("now", GraphRunPersistence.timestamp(now));
                jdbc.update("""
                        UPDATE agent_run SET status=:status,result_status=:result,error_code=:error,output_snapshot_json=CAST(:output AS jsonb),
                            completed_at=:now,updated_at=:now,lease_owner=NULL,lease_expires_at=NULL,pause_requested_at=NULL
                        WHERE tenant_code=:tenant AND id=:run
                        """, key);
                jdbc.update("""
                        UPDATE agent_run_attempt SET status=:status,error_code=:error,completed_at=:now,updated_at=:now
                        WHERE tenant_code=:tenant AND agent_run_id=:run AND id=:attempt
                        """, key);
                GraphRunPersistence.history(jdbc, run.stateHistory().getLast());
                publisher.completed(run, output);
                mark(key, "COMMITTED", reason);
                return true;
            }));
        } catch (RuntimeException failure) {
            // The acceptance transaction has rolled back. Persist a bounded backoff separately;
            // another worker may have completed meanwhile, so never rewrite a completed ledger.
            if (selected.get() != null) {
                try {
                    transaction.executeWithoutResult(tx -> jdbc.update("""
                            UPDATE agent_graph_dispatch SET completion_failures=LEAST(completion_failures+1,100000),
                                completion_reason='GRAPH_COMPLETION_RETRY',
                                available_at=clock_timestamp()+LEAST(60,power(2,LEAST(completion_failures,6))) * interval '1 second'
                            WHERE tenant_code=:tenant AND run_id=:run AND status='ACKNOWLEDGED' AND completion_status IS NULL
                            """, selected.get()));
                } catch (RuntimeException unavailable) { failure.addSuppressed(unavailable); }
            }
            throw failure;
        }
    }

    private void mark(MapSqlParameterSource key, String state, String reason) {
        jdbc.update("""
                UPDATE agent_graph_dispatch SET completion_status=:completion,completion_reason=:reason,completed_at=clock_timestamp()
                WHERE tenant_code=:tenant AND run_id=:run
                """, key.addValue("completion", state).addValue("reason", reason));
    }
    private record Pending(boolean valid, String observation,
                           io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun run) { }
}

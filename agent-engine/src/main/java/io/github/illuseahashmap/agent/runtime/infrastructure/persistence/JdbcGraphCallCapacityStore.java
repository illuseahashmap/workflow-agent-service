package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.reference;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.port.GraphCallLifecyclePort;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** All capacity decisions are serialized in short DB transactions; no network I/O under a lock. */
public final class JdbcGraphCallCapacityStore implements GraphCallLifecyclePort {
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcGraphExecutionStore executions;
    private final GraphGatewayCodec codec;
    private final TransactionTemplate transaction;

    public JdbcGraphCallCapacityStore(
            NamedParameterJdbcTemplate jdbc,
            JdbcGraphExecutionStore executions,
            GraphGatewayCodec codec) {
        executions.requireSameDataSource(jdbc);
        this.jdbc = jdbc;
        this.executions = executions;
        this.codec = codec;
        transaction =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource())));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }

    @Override
    public Optional<String> acquire(
            AuthorizedRun run,
            Invocation operation,
            java.util.function.Supplier<List<String>> resolver) {
        return acquire(run, operation, resolver, 0);
    }

    @Override
    public Optional<String> acquireTracked(
            AuthorizedRun run,
            Invocation operation,
            java.util.function.Supplier<List<String>> resolver) {
        return acquire(run, operation, resolver, 1);
    }

    private Optional<String> acquire(
            AuthorizedRun run,
            Invocation operation,
            java.util.function.Supplier<List<String>> resolver,
            int tracking) {
        return transaction.execute(
                status -> {
                    var policy = GraphCapacityLock.lock(jdbc);
                    var resources = resolver.get();
                    require(resources != null && !resources.isEmpty() && resources.size() <= 8);
                    resources.forEach(
                            key -> {
                                reference(key);
                                require(key.length() <= 256);
                            });
                    var keys = resources.stream().distinct().sorted().toList();
                    var params =
                            new MapSqlParameterSource()
                                    .addValue("tenant", run.binding().tenantCode())
                                    .addValue("run", run.binding().runId())
                                    .addValue("operation", operation.operationId())
                                    .addValue("generation", run.generation())
                                    .addValue("digest", codec.fingerprint(operation))
                                    .addValue("tracking", tracking);
                    jdbc.query(
                            "SELECT id FROM agent_run WHERE tenant_code=:tenant AND id=:run FOR"
                                + " UPDATE",
                            params,
                            (rs, n) -> rs.getLong(1));
                    executions.verifyCurrent(run);
                    // Surviving ACTIVE records after a crash remain counted; successors cannot
                    // steal external execution.
                    jdbc.update(
                            """
                            UPDATE agent_graph_call_permit SET state='QUARANTINED'
                            WHERE tenant_code=:tenant AND run_id=:run AND generation<>:generation AND state='ACTIVE'
                            """,
                            params);
                    var old =
                            jdbc.query(
                                    """
                                    SELECT request_digest FROM agent_graph_call_permit
                                    WHERE tenant_code=:tenant AND run_id=:run AND operation_id=:operation
                                    """,
                                    params,
                                    (rs, n) -> rs.getString(1));
                    require(old.stream().allMatch(codec.fingerprint(operation)::equals));
                    long all =
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM agent_graph_call_permit WHERE"
                                        + " state<>'RELEASED'",
                                    Map.of(),
                                    Long.class);
                    long tenant =
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM agent_graph_call_permit WHERE"
                                        + " tenant_code=:tenant AND state<>'RELEASED'",
                                    params,
                                    Long.class);
                    long pending =
                            jdbc.queryForObject(
                                    """
                                    SELECT count(*) FROM agent_graph_call_permit WHERE tenant_code=:tenant AND run_id=:run
                                      AND operation_id=:operation AND state<>'RELEASED'
                                    """,
                                    params,
                                    Long.class);
                    if (pending > 0 || all >= policy.calls()
                            || tenant >= GraphCapacityLock.tenant(jdbc, policy, run.binding().tenantCode()).tenantCalls()) {
                        return Optional.empty();
                    }
                    for (String key : keys) {
                        params.addValue("resource", key);
                        var limits =
                                jdbc.query(
                                        "SELECT max_calls FROM agent_graph_call_resource WHERE"
                                            + " resource_key=:resource",
                                        params,
                                        (rs, n) -> rs.getInt(1));
                        require(limits.size() == 1); // Missing account mapping/policy fails closed.
                        long count =
                                jdbc.queryForObject(
                                        """
                                        SELECT count(*) FROM agent_graph_call_allocation a JOIN agent_graph_call_permit p USING(token)
                                        WHERE a.resource_key=:resource AND p.state<>'RELEASED'
                                        """,
                                        params,
                                        Long.class);
                        if (count >= limits.getFirst()) {
                            return Optional.empty();
                        }
                    }
                    String token = UUID.randomUUID().toString();
                    params.addValue("token", UUID.fromString(token));
                    jdbc.update(
                            """
                            INSERT INTO agent_graph_call_permit(token,tenant_code,run_id,operation_id,request_digest,generation,state,tracking_version)
                            VALUES(:token,:tenant,:run,:operation,:digest,:generation,'ACTIVE',:tracking)
                            """,
                            params);
                    for (String key : keys) {
                        jdbc.update(
                                "INSERT INTO agent_graph_call_allocation VALUES(:token,:resource)",
                                params.addValue("resource", key));
                    }
                    executions.verifyCurrent(run);
                    return Optional.of(token);
                });
    }

    @Override
    public void completed(String token) {
        settle(token, "RELEASED", "gateway-completed", false);
    }

    @Override
    public void uncertain(String token) {
        settle(token, "QUARANTINED", "gateway-outcome-unknown", false);
    }

    @Override
    public void recovered(AuthorizedRun run, Invocation operation, String receipt) {
        reference(receipt);
        require(receipt.length() <= 128);
        transaction.executeWithoutResult(
                status -> {
                    GraphCapacityLock.lock(jdbc);
                    var params =
                            new MapSqlParameterSource()
                                    .addValue("tenant", run.binding().tenantCode())
                                    .addValue("run", run.binding().runId())
                                    .addValue("operation", operation.operationId())
                                    .addValue("digest", codec.fingerprint(operation))
                                    .addValue("receipt", receipt);
                    jdbc.query(
                            "SELECT id FROM agent_run WHERE tenant_code=:tenant AND id=:run FOR"
                                + " UPDATE",
                            params,
                            (rs, n) -> rs.getLong(1));
                    executions.verifyCurrent(run);
                    jdbc.update(
                            """
                            UPDATE agent_graph_call_permit SET state='RELEASED',resolution=:receipt,settled_at=clock_timestamp()
                            WHERE tenant_code=:tenant AND run_id=:run AND operation_id=:operation AND request_digest=:digest AND state<>'RELEASED' AND tracking_version=0
                            """,
                            params);
                });
    }

    @Override
    public boolean reconcile(String token, String proof) {
        reference(proof);
        require(proof.length() <= 128);
        return settle(token, "RELEASED", proof, true);
    }

    @Override
    public void dispatching(
            String token, AuthorizedRun run, Invocation operation, String budgetCallAttempt) {
        transaction.executeWithoutResult(
                status -> {
                    var key =
                            new MapSqlParameterSource("tenant", run.binding().tenantCode())
                                    .addValue("run", run.binding().runId());
                    jdbc.query(
                            "SELECT id FROM agent_run WHERE tenant_code=:tenant AND id=:run FOR"
                                + " UPDATE",
                            key,
                            (rs, n) -> rs.getLong(1));
                    executions.verifyCurrent(run);
                    if (run.binding().stateSchemaVersion() == 2) {
                        require(
                                new JdbcGraphPlanStore(jdbc, executions, codec)
                                                .guard(run, operation)
                                        == null);
                    }
                    var permit = permit(token);
                    require(
                            permit.tracking() == 1
                                    && "ACTIVE".equals(permit.state())
                                    && permit.tenant().equals(run.binding().tenantCode())
                                    && permit.run() == run.binding().runId()
                                    && permit.generation() == run.generation()
                                    && permit.operation().equals(operation.operationId())
                                    && permit.digest().equals(codec.fingerprint(operation)));
                    require(!dispatched(permit) && outcome(permit) == null);
                    UUID attempt =
                            budgetCallAttempt == null ? null : UUID.fromString(budgetCallAttempt);
                    if (run.binding().stateSchemaVersion() == 2) {
                        require(
                                attempt != null
                                        && jdbc.queryForObject(
                                                        """
                                                        SELECT count(*) FROM agent_graph_budget_call WHERE tenant_code=:tenant AND run_id=:run
                                                          AND call_attempt_id=:attempt AND operation_id=:operation AND generation=:generation
                                                        """,
                                                        key.addValue("attempt", attempt)
                                                                .addValue(
                                                                        "operation",
                                                                        operation.operationId())
                                                                .addValue(
                                                                        "generation",
                                                                        run.generation()),
                                                        Integer.class)
                                                == 1);
                    } else {
                        require(attempt == null);
                    }
                    jdbc.update(
                            "INSERT INTO"
                                + " agent_graph_call_dispatch(token,tenant_code,run_id,budget_call_attempt)"
                                + " VALUES(:token,:tenant,:run,:attempt)",
                            params(permit).addValue("attempt", attempt));
                    executions.verifyCurrent(run);
                });
    }

    @Override
    public void returned(String token) {
        transaction.executeWithoutResult(
                status -> {
                    GraphCapacityLock.lock(jdbc);
                    var permit = permit(token);
                    require(permit.tracking() == 1 && dispatched(permit));
                    recordOutcome(permit, "RETURNED");
                    release(permit, "physical-returned");
                });
    }

    @Override
    public void finished(String token) {
        transaction.executeWithoutResult(
                status -> {
                    GraphCapacityLock.lock(jdbc);
                    var permit = permit(token);
                    require(permit.tracking() == 1);
                    resolve(permit, true, true);
                });
    }

    @Override
    public void recoverTracked(AuthorizedRun run, Invocation operation) {
        transaction.executeWithoutResult(
                status -> {
                    GraphCapacityLock.lock(jdbc);
                    var key =
                            new MapSqlParameterSource("tenant", run.binding().tenantCode())
                                    .addValue("run", run.binding().runId())
                                    .addValue("op", operation.operationId());
                    jdbc.query(
                            "SELECT id FROM agent_run WHERE tenant_code=:tenant AND id=:run FOR"
                                + " UPDATE",
                            key,
                            (rs, n) -> rs.getLong(1));
                    executions.verifyCurrent(run);
                    var tokens =
                            jdbc.query(
                                    "SELECT token::text FROM agent_graph_call_permit WHERE"
                                        + " tenant_code=:tenant AND run_id=:run AND"
                                        + " operation_id=:op AND tracking_version=1 AND"
                                        + " state<>'RELEASED'",
                                    key,
                                    (rs, n) -> rs.getString(1));
                    for (var token : tokens) {
                        var permit = permit(token);
                        require(permit.digest().equals(codec.fingerprint(operation)));
                        boolean fenced = permit.generation() != run.generation();
                        resolve(permit, fenced, fenced);
                    }
                });
    }

    /** Resolve proven unsent orphans; dispatched unknown work remains counted indefinitely. */
    public int quarantineOrphans(int limit) {
        require(limit > 0 && limit <= 1000);
        return transaction.execute(
                status -> {
                    GraphCapacityLock.lock(jdbc);
                    var tokens =
                            jdbc.query(
                                    """
                                    SELECT p.token::text FROM agent_graph_call_permit p JOIN agent_run r ON r.tenant_code=p.tenant_code AND r.id=p.run_id
                                    WHERE (p.state='ACTIVE' OR (p.state='QUARANTINED' AND p.tracking_version=1
                                        AND NOT EXISTS(SELECT 1 FROM agent_graph_call_dispatch x WHERE x.token=p.token)))
                                      AND NOT EXISTS(SELECT 1 FROM agent_graph_execution e JOIN agent_graph_dispatch d USING(tenant_code,run_id)
                                        WHERE e.tenant_code=p.tenant_code AND e.run_id=p.run_id AND e.generation=p.generation
                                        AND e.state='ACTIVE' AND d.status='RUNNING' AND e.lease_expires_at>clock_timestamp()
                                        AND NOT EXISTS(SELECT 1 FROM agent_graph_run_stop s WHERE s.tenant_code=e.tenant_code AND s.run_id=e.run_id))
                                    ORDER BY p.claimed_at,p.token LIMIT :limit FOR UPDATE OF r,p SKIP LOCKED
                                    """,
                                    Map.of("limit", limit),
                                    (rs, n) -> rs.getString(1));
                    int changed = 0;
                    for (var token : tokens) {
                        var permit = permit(token);
                        boolean live =
                                Boolean.TRUE.equals(
                                        jdbc.queryForObject(
                                                """
                                                SELECT EXISTS(SELECT 1 FROM agent_graph_execution e JOIN agent_graph_dispatch d USING(tenant_code,run_id)
                                                  WHERE e.tenant_code=:tenant AND e.run_id=:run AND e.generation=:generation
                                                    AND e.state='ACTIVE' AND d.status='RUNNING' AND e.lease_expires_at>clock_timestamp()
                                                    AND NOT EXISTS(SELECT 1 FROM agent_graph_run_stop s WHERE s.tenant_code=e.tenant_code AND s.run_id=e.run_id))
                                                """,
                                                params(permit)
                                                        .addValue(
                                                                "generation", permit.generation()),
                                                Boolean.class));
                        if (!live) {
                            changed +=
                                    permit.tracking() == 1
                                            ? resolve(permit, true, true)
                                            : quarantine(permit);
                        }
                    }
                    return changed;
                });
    }

    private int resolve(Permit permit, boolean allowUnsent, boolean quarantineUnknown) {
        if ("RELEASED".equals(permit.state())) {
            return 0;
        }
        if ("RETURNED".equals(outcome(permit))) {
            return release(permit, "physical-returned");
        }
        if (allowUnsent && !dispatched(permit)) {
            recordOutcome(permit, "NOT_DISPATCHED");
            return release(permit, "send-fenced-not-dispatched");
        }
        return quarantineUnknown ? quarantine(permit) : 0;
    }

    private int release(Permit permit, String reason) {
        return jdbc.update(
                "UPDATE agent_graph_call_permit SET"
                    + " state='RELEASED',resolution=:reason,settled_at=clock_timestamp() WHERE"
                    + " token=:token AND state<>'RELEASED'",
                params(permit).addValue("reason", reason));
    }

    private int quarantine(Permit permit) {
        return jdbc.update(
                "UPDATE agent_graph_call_permit SET"
                    + " state='QUARANTINED',resolution='physical-outcome-unknown' WHERE"
                    + " token=:token AND state='ACTIVE'",
                params(permit));
    }

    private boolean dispatched(Permit permit) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM agent_graph_call_dispatch WHERE token=:token",
                        params(permit),
                        Integer.class)
                == 1;
    }

    private String outcome(Permit permit) {
        var outcomes =
                jdbc.query(
                        "SELECT outcome FROM agent_graph_call_outcome WHERE token=:token",
                        params(permit),
                        (rs, n) -> rs.getString(1));
        return outcomes.isEmpty() ? null : outcomes.getFirst();
    }

    private void recordOutcome(Permit permit, String value) {
        jdbc.update(
                "INSERT INTO agent_graph_call_outcome(token,tenant_code,run_id,outcome)"
                    + " VALUES(:token,:tenant,:run,:outcome) ON CONFLICT DO NOTHING",
                params(permit).addValue("outcome", value));
        require(value.equals(outcome(permit)));
    }

    private MapSqlParameterSource params(Permit permit) {
        return new MapSqlParameterSource("token", permit.token())
                .addValue("tenant", permit.tenant())
                .addValue("run", permit.run());
    }

    private Permit permit(String token) {
        return jdbc.queryForObject(
                "SELECT * FROM agent_graph_call_permit WHERE token=:token FOR UPDATE",
                Map.of("token", UUID.fromString(token)),
                (rs, n) ->
                        new Permit(
                                UUID.fromString(rs.getString("token")),
                                rs.getString("tenant_code"),
                                rs.getLong("run_id"),
                                rs.getString("operation_id"),
                                rs.getString("request_digest"),
                                rs.getLong("generation"),
                                rs.getString("state"),
                                rs.getInt("tracking_version")));
    }

    private record Permit(
            UUID token,
            String tenant,
            long run,
            String operation,
            String digest,
            long generation,
            String state,
            int tracking) {}

    private boolean settle(String token, String target, String reason, boolean recovery) {
        return Boolean.TRUE.equals(
                transaction.execute(
                        status -> {
                            GraphCapacityLock.lock(jdbc);
                            return jdbc.update(
                                            """
                                            UPDATE agent_graph_call_permit SET state=:state,resolution=:reason,settled_at=clock_timestamp()
                                            WHERE token=:token AND state<>'RELEASED' AND (:recovery OR tracking_version=0) AND (:recovery OR state='ACTIVE' OR :state='RELEASED')
                                            """,
                                            Map.of(
                                                    "token",
                                                    UUID.fromString(token),
                                                    "state",
                                                    target,
                                                    "reason",
                                                    reason,
                                                    "recovery",
                                                    recovery))
                                    == 1;
                        }));
    }
}

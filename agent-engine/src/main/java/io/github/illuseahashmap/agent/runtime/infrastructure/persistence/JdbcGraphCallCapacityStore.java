package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.port.GraphCallCapacityPort;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.reference;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

/** All capacity decisions are serialized in short DB transactions; no network I/O under a lock. */
public final class JdbcGraphCallCapacityStore implements GraphCallCapacityPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcGraphExecutionStore executions;
    private final GraphGatewayCodec codec;
    private final TransactionTemplate transaction;

    public JdbcGraphCallCapacityStore(NamedParameterJdbcTemplate jdbc, JdbcGraphExecutionStore executions, GraphGatewayCodec codec) {
        executions.requireSameDataSource(jdbc);
        this.jdbc = jdbc;
        this.executions = executions;
        this.codec = codec;
        transaction = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource())));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }

    @Override public Optional<String> acquire(AuthorizedRun run, Invocation operation, java.util.function.Supplier<List<String>> resolver) {
        return transaction.execute(status -> {
            var policy = GraphCapacityLock.lock(jdbc);
            var resources = resolver.get();
            require(resources != null && !resources.isEmpty() && resources.size() <= 8);
            resources.forEach(key -> {
                reference(key);
                require(key.length() <= 256);
            });
            var keys = resources.stream().distinct().sorted().toList();
            var params = new MapSqlParameterSource().addValue("tenant", run.binding().tenantCode())
                    .addValue("run", run.binding().runId()).addValue("operation", operation.operationId())
                    .addValue("generation", run.generation()).addValue("digest", codec.fingerprint(operation));
            jdbc.query("SELECT id FROM agent_run WHERE tenant_code=:tenant AND id=:run FOR UPDATE", params, (rs, n) -> rs.getLong(1));
            executions.verifyCurrent(run);
            // Surviving ACTIVE records after a crash remain counted; successors cannot steal external execution.
            jdbc.update("""
                    UPDATE agent_graph_call_permit SET state='QUARANTINED'
                    WHERE tenant_code=:tenant AND run_id=:run AND generation<>:generation AND state='ACTIVE'
                    """, params);
            var old = jdbc.query("""
                    SELECT request_digest FROM agent_graph_call_permit
                    WHERE tenant_code=:tenant AND run_id=:run AND operation_id=:operation
                    """, params, (rs, n) -> rs.getString(1));
            require(old.stream().allMatch(codec.fingerprint(operation)::equals));
            long all = jdbc.queryForObject("SELECT count(*) FROM agent_graph_call_permit WHERE state<>'RELEASED'", Map.of(), Long.class);
            long tenant = jdbc.queryForObject("SELECT count(*) FROM agent_graph_call_permit WHERE tenant_code=:tenant AND state<>'RELEASED'", params, Long.class);
            long pending = jdbc.queryForObject("""
                    SELECT count(*) FROM agent_graph_call_permit WHERE tenant_code=:tenant AND run_id=:run
                      AND operation_id=:operation AND state<>'RELEASED'
                    """, params, Long.class);
            if (pending > 0 || all >= policy.calls() || tenant >= policy.tenantCalls()) { return Optional.empty(); }
            for (String key : keys) {
                params.addValue("resource", key);
                var limits = jdbc.query("SELECT max_calls FROM agent_graph_call_resource WHERE resource_key=:resource", params, (rs, n) -> rs.getInt(1));
                require(limits.size() == 1); // Missing account mapping/policy fails closed.
                long count = jdbc.queryForObject("""
                        SELECT count(*) FROM agent_graph_call_allocation a JOIN agent_graph_call_permit p USING(token)
                        WHERE a.resource_key=:resource AND p.state<>'RELEASED'
                        """, params, Long.class);
                if (count >= limits.getFirst()) { return Optional.empty(); }
            }
            String token = UUID.randomUUID().toString();
            params.addValue("token", UUID.fromString(token));
            jdbc.update("""
                    INSERT INTO agent_graph_call_permit(token,tenant_code,run_id,operation_id,request_digest,generation,state)
                    VALUES(:token,:tenant,:run,:operation,:digest,:generation,'ACTIVE')
                    """, params);
            for (String key : keys) {
                jdbc.update("INSERT INTO agent_graph_call_allocation VALUES(:token,:resource)", params.addValue("resource", key));
            }
            executions.verifyCurrent(run);
            return Optional.of(token);
        });
    }

    @Override public void completed(String token) { settle(token, "RELEASED", "gateway-completed", false); }
    @Override public void uncertain(String token) { settle(token, "QUARANTINED", "gateway-outcome-unknown", false); }
    @Override public void recovered(AuthorizedRun run, Invocation operation, String receipt) {
        reference(receipt);
        require(receipt.length() <= 128);
        transaction.executeWithoutResult(status -> {
            GraphCapacityLock.lock(jdbc);
            var params = new MapSqlParameterSource().addValue("tenant", run.binding().tenantCode())
                    .addValue("run", run.binding().runId()).addValue("operation", operation.operationId())
                    .addValue("digest", codec.fingerprint(operation)).addValue("receipt", receipt);
            jdbc.query("SELECT id FROM agent_run WHERE tenant_code=:tenant AND id=:run FOR UPDATE", params, (rs, n) -> rs.getLong(1));
            executions.verifyCurrent(run);
            jdbc.update("""
                    UPDATE agent_graph_call_permit SET state='RELEASED',resolution=:receipt,settled_at=clock_timestamp()
                    WHERE tenant_code=:tenant AND run_id=:run AND operation_id=:operation AND request_digest=:digest AND state<>'RELEASED'
                    """, params);
        });
    }
    @Override public boolean reconcile(String token, String proof) {
        reference(proof);
        require(proof.length() <= 128);
        return settle(token, "RELEASED", proof, true);
    }

    /** Bounded recovery classification only. Does not free capacity or assert remote cancellation. */
    public int quarantineOrphans(int limit) {
        require(limit > 0 && limit <= 1000);
        return transaction.execute(status -> {
            GraphCapacityLock.lock(jdbc);
            return jdbc.update("""
                    UPDATE agent_graph_call_permit SET state='QUARANTINED',resolution='execution-authority-lost'
                    WHERE token IN (SELECT p.token FROM agent_graph_call_permit p
                        WHERE p.state='ACTIVE' AND NOT EXISTS (
                            SELECT 1 FROM agent_graph_execution e JOIN agent_graph_dispatch d USING(tenant_code,run_id)
                            WHERE e.tenant_code=p.tenant_code AND e.run_id=p.run_id AND e.generation=p.generation
                                AND e.state='ACTIVE' AND d.status='RUNNING' AND e.lease_expires_at>clock_timestamp())
                        ORDER BY p.claimed_at,p.token LIMIT :limit)
                    """, Map.of("limit", limit));
        });
    }
    private boolean settle(String token, String target, String reason, boolean recovery) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            GraphCapacityLock.lock(jdbc);
            return jdbc.update("""
                    UPDATE agent_graph_call_permit SET state=:state,resolution=:reason,settled_at=clock_timestamp()
                    WHERE token=:token AND state<>'RELEASED' AND (:recovery OR state='ACTIVE' OR :state='RELEASED')
                    """, Map.of("token", UUID.fromString(token), "state", target, "reason", reason, "recovery", recovery)) == 1;
        }));
    }
}

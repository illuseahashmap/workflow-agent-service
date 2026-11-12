package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Binding;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException;
import io.github.illuseahashmap.agent.runtime.application.port.GraphDispatchPort;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.digest;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.reference;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.AUTHORITY_DENIED;

/** Not a Spring bean until MODEL/VALIDATE and admission gates are ready. No permissive fallback. */
public final class JdbcGraphExecutionStore implements GraphDispatchPort, GraphRunAuthorityPort {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String LIVE = """
            FROM agent_run r JOIN agent_graph_execution e ON e.tenant_code=r.tenant_code AND e.run_id=r.id
            JOIN agent_graph_dispatch d ON d.tenant_code=e.tenant_code AND d.run_id=e.run_id
            WHERE r.tenant_code=:tenant AND r.id=:run AND r.execution_backend='LANGGRAPH'
              AND r.status IN ('QUEUED','RUNNING') AND r.deadline_at>clock_timestamp()
              AND e.state='ACTIVE' AND d.status='RUNNING'
              AND e.lease_expires_at>clock_timestamp() AND e.generation=:generation
              AND r.requested_by=e.principal_id AND r.input_snapshot_json::text=e.input_json
              AND r.process_instance_id IS NOT DISTINCT FROM e.process_instance_id
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final GraphGatewayCodec codec;
    private final TransactionTemplate transaction;

    public JdbcGraphExecutionStore(NamedParameterJdbcTemplate jdbc, GraphGatewayCodec codec) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.codec = Objects.requireNonNull(codec);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource())));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }

    @Override public Binding reserve(String tenant, long run, String artifact, int modelBudget, int toolBudget) {
        reference(tenant);
        digest(artifact);
        require(run > 0 && modelBudget > 0 && modelBudget <= 100 && toolBudget >= 0 && toolBudget <= 200);
        return atomic(() -> {
            lockRun(tenant, run);
            var key = Map.of("tenant", tenant, "run", run);
            var existing = jdbc.query("SELECT binding_json FROM agent_graph_execution WHERE tenant_code=:tenant AND run_id=:run",
                    key, (rs, n) -> decode(rs.getString(1)));
            if (!existing.isEmpty()) {
                Binding old = existing.getFirst();
                require(old.graphArtifactDigest().equals(artifact) && old.maxModelCalls() == modelBudget && old.maxToolCalls() == toolBudget);
                return old;
            }
            var source = jdbc.query("""
                    SELECT r.*, v.tool_set_json::text AS tools, r.input_snapshot_json::text AS input,
                           (r.input_snapshot_json->'nodeToolSet')::text AS node_tools
                    FROM agent_run r JOIN agent_definition_version v ON v.id=r.agent_version_id AND v.tenant_code=r.tenant_code
                    WHERE r.tenant_code=:tenant AND r.id=:run AND r.status='QUEUED' AND r.execution_backend='NATIVE'
                      AND r.current_attempt_id IS NULL AND r.deadline_at>clock_timestamp() AND v.status='PUBLISHED'
                      AND NOT EXISTS (SELECT 1 FROM agent_run_attempt a WHERE a.tenant_code=r.tenant_code AND a.agent_run_id=r.id)
                    """, key, (rs, n) -> new Source(rs.getLong("agent_version_id"), rs.getString("input"),
                    rs.getString("requested_by"), rs.getString("process_instance_id"), rs.getString("tools"),
                    rs.getString("node_tools"), rs.getObject("deadline_at", OffsetDateTime.class).toInstant()));
            if (source.size() != 1) { throw denied(); }
            Source s = source.getFirst();
            require(s.principal() != null && !s.principal().isBlank() && s.input() != null);
            codec.arguments(s.input());
            String scope = tenant + ":" + run + ":";
            var binding = new Binding(tenant, run, s.version(), artifact, 1,
                    "input:" + GraphGatewayCodec.sha256(scope + s.input()),
                    "subject:" + GraphGatewayCodec.sha256(scope + s.principal()), s.deadline().toEpochMilli(), modelBudget, toolBudget);
            var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(key)
                    .addValue("binding", json(binding)).addValue("digest", codec.fingerprint(binding)).addValue("input", s.input())
                    .addValue("principal", s.principal()).addValue("process", s.process()).addValue("tools", s.tools())
                    .addValue("nodeTools", "null".equals(s.nodeTools()) ? null : s.nodeTools()).addValue("command", UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO agent_graph_execution(tenant_code,run_id,binding_json,binding_digest,input_json,principal_id,
                        process_instance_id,version_tools_json,node_tools_json)
                    VALUES (:tenant,:run,:binding,:digest,:input,:principal,:process,:tools,:nodeTools)
                    """, params);
            jdbc.update("INSERT INTO agent_graph_dispatch(tenant_code,run_id,command_id,status) VALUES (:tenant,:run,:command,'READY')", params);
            jdbc.update("UPDATE agent_run SET execution_backend='LANGGRAPH' WHERE tenant_code=:tenant AND id=:run", key);
            return binding;
        });
    }

    @Override public Optional<Claim> claim(String tenant, String worker, int seconds) {
        reference(tenant);
        require(worker != null && worker.matches("[A-Za-z0-9_.:-]{1,128}"));
        lease(seconds);
        return atomic(() -> {
            var candidates = jdbc.query("""
                    SELECT r.id FROM agent_run r
                    JOIN agent_graph_execution e ON e.tenant_code=r.tenant_code AND e.run_id=r.id
                    JOIN agent_graph_dispatch d ON d.tenant_code=e.tenant_code AND d.run_id=e.run_id
                    WHERE r.tenant_code=:tenant AND r.execution_backend='LANGGRAPH' AND r.status IN ('QUEUED','RUNNING')
                      AND r.deadline_at>clock_timestamp() AND e.state='ACTIVE' AND d.available_at<=clock_timestamp()
                      AND (d.status='READY' OR (d.status='RUNNING' AND e.lease_expires_at<=clock_timestamp()))
                    ORDER BY d.available_at,r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED
                    """, Map.of("tenant", tenant), (rs, n) -> rs.getLong(1));
            if (candidates.isEmpty()) { return Optional.empty(); }
            long run = candidates.getFirst();
            byte[] entropy = new byte[32];
            RANDOM.nextBytes(entropy);
            String capability = "graph." + HexFormat.of().formatHex(entropy);
            var params = Map.of("tenant", tenant, "run", run, "worker", worker, "seconds", seconds,
                    "hash", GraphGatewayCodec.sha256(capability));
            jdbc.update("""
                    UPDATE agent_graph_execution e SET generation=generation+1, capability_hash=:hash,lease_owner=:worker,
                        lease_expires_at=LEAST(clock_timestamp()+:seconds*interval '1 second',r.deadline_at)
                    FROM agent_run r WHERE e.tenant_code=:tenant AND e.run_id=:run AND r.id=e.run_id AND r.tenant_code=e.tenant_code
                    """, params);
            jdbc.update("UPDATE agent_graph_dispatch SET status='RUNNING' WHERE tenant_code=:tenant AND run_id=:run", params);
            return jdbc.query("""
                    SELECT e.*,d.command_id,d.checkpoint_id,d.wait_id FROM agent_graph_execution e
                    JOIN agent_graph_dispatch d USING(tenant_code,run_id) WHERE tenant_code=:tenant AND run_id=:run
                    """, params, (rs, n) -> new Claim(rs.getString("command_id"), decode(rs.getString("binding_json")),
                    rs.getLong("generation"), capability, worker, rs.getObject("lease_expires_at", OffsetDateTime.class).toInstant(),
                    rs.getString("checkpoint_id"), rs.getString("wait_id"))).stream().findFirst();
        });
    }

    @Override public AuthorizedRun authorize(String token, Binding submitted, long generation) {
        if (token == null || !token.matches("graph\\.[0-9a-f]{64}")) { throw denied(); }
        var params = Map.of("tenant", submitted.tenantCode(), "run", submitted.runId(), "generation", generation,
                "hash", GraphGatewayCodec.sha256(token));
        var result = jdbc.query("SELECT e.*,r.agent_version_id,r.deadline_at " + LIVE + " AND e.capability_hash=:hash",
                params, (rs, n) -> authority(rs));
        if (result.size() != 1 || !submitted.equals(result.getFirst().binding())) { throw denied(); }
        return result.getFirst();
    }

    @Override public boolean renew(Claim claim, int seconds) {
        lease(seconds);
        return atomic(() -> {
            if (!lockClaim(claim)) { return false; }
            int updated = jdbc.update("""
                    UPDATE agent_graph_execution e SET lease_expires_at=LEAST(clock_timestamp()+:seconds*interval '1 second',r.deadline_at)
                    FROM agent_run r WHERE e.tenant_code=:tenant AND e.run_id=:run AND r.id=e.run_id AND r.tenant_code=e.tenant_code
                      AND e.lease_expires_at>clock_timestamp() AND r.deadline_at>clock_timestamp()
                    """, Map.of("tenant", claim.binding().tenantCode(), "run", claim.binding().runId(), "seconds", seconds));
            return updated == 1;
        });
    }

    @Override public boolean defer(Claim claim, String checkpoint, String wait, Instant available) {
        reference(checkpoint);
        reference(wait);
        require(available != null && available.toEpochMilli() < claim.binding().deadlineEpochMs());
        return atomic(() -> {
            if (!lockClaim(claim)) { return false; }
            int updated = jdbc.update("""
                    UPDATE agent_graph_dispatch d SET status='READY',command_id=:command,checkpoint_id=:checkpoint,wait_id=:wait,
                        available_at=:available WHERE tenant_code=:tenant AND run_id=:run
                        AND EXISTS (SELECT 1 FROM agent_graph_execution e WHERE e.tenant_code=d.tenant_code AND e.run_id=d.run_id
                            AND e.lease_expires_at>clock_timestamp())
                    """, Map.of("tenant", claim.binding().tenantCode(), "run", claim.binding().runId(), "command", UUID.randomUUID(),
                    "checkpoint", checkpoint, "wait", wait, "available", OffsetDateTime.ofInstant(available, java.time.ZoneOffset.UTC)));
            if (updated != 1) { return false; }
            clearCapability(claim.binding());
            return true;
        });
    }

    @Override public boolean acknowledge(Claim claim, Observation observation) {
        require(observation != null && ("CANDIDATE_READY".equals(observation.status()) || "REJECTED".equals(observation.status())));
        reference(observation.checkpointId());
        reference(observation.candidateRef());
        reference(observation.validationReceiptRef());
        String payload = json(observation);
        return atomic(() -> {
            if (!lockClaim(claim)) { return false; }
            int updated = jdbc.update("""
                    UPDATE agent_graph_dispatch d SET status='ACKNOWLEDGED',observation_json=:body WHERE tenant_code=:tenant AND run_id=:run
                        AND EXISTS (SELECT 1 FROM agent_graph_execution e WHERE e.tenant_code=d.tenant_code AND e.run_id=d.run_id
                            AND e.lease_expires_at>clock_timestamp())
                    """,
                    Map.of("tenant", claim.binding().tenantCode(), "run", claim.binding().runId(), "body", payload));
            if (updated != 1) { return false; }
            clearCapability(claim.binding());
            return true;
        });
    }

    @Override public void revoke(String tenant, long run) {
        atomic(() -> {
            lockRun(tenant, run);
            jdbc.update("""
                    UPDATE agent_graph_execution SET state='REVOKED',capability_hash=NULL,lease_owner=NULL,lease_expires_at=NULL
                    WHERE tenant_code=:tenant AND run_id=:run
                    """, Map.of("tenant", tenant, "run", run));
            return null;
        });
    }

    @Override public Optional<DispatchView> inspect(String tenant, long run) {
        return jdbc.query("""
                SELECT d.command_id,d.status,e.state,e.generation,d.available_at,d.observation_json
                FROM agent_graph_dispatch d JOIN agent_graph_execution e USING(tenant_code,run_id)
                WHERE d.tenant_code=:tenant AND d.run_id=:run
                """, Map.of("tenant", tenant, "run", run), (rs, n) -> {
                    String observation = rs.getString("observation_json");
                    return new DispatchView(rs.getString("command_id"), rs.getString("status"), rs.getString("state"),
                            rs.getLong("generation"), rs.getObject("available_at", OffsetDateTime.class).toInstant(),
                            observation == null ? null : codec.read(observation.getBytes(StandardCharsets.UTF_8), Observation.class));
                }).stream().findFirst();
    }

    void requireSameDataSource(NamedParameterJdbcTemplate other) {
        if (jdbc.getJdbcTemplate().getDataSource() != other.getJdbcTemplate().getDataSource()) {
            throw new IllegalArgumentException("Graph references and execution fencing must share one transactional DataSource");
        }
    }

    /** Same lock order as dispatch/cancellation: Run row first, no remote IO while held. */
    <T> T withCurrentRun(AuthorizedRun expected, Supplier<T> work) {
        return atomic(() -> {
            lockRun(expected.binding().tenantCode(), expected.binding().runId());
            verifyCurrent(expected);
            T result = work.get();
            // Time can expire while SQL is blocked even though the Run lock excludes a new owner.
            // Reject the entire reference transaction rather than commit a late successful write.
            verifyCurrent(expected);
            return result;
        });
    }

    private void verifyCurrent(AuthorizedRun expected) {
        var rows = jdbc.query("SELECT e.*,r.agent_version_id,r.deadline_at " + LIVE,
                Map.of("tenant", expected.binding().tenantCode(), "run", expected.binding().runId(), "generation", expected.generation()),
                (rs, n) -> authority(rs));
        if (rows.size() != 1 || !expected.equals(rows.getFirst())) { throw denied(); }
    }

    private AuthorizedRun authority(ResultSet rs) throws SQLException {
        Binding binding = decode(rs.getString("binding_json"));
        if (binding.agentVersionId() != rs.getLong("agent_version_id")
                || binding.deadlineEpochMs() != rs.getObject("deadline_at", OffsetDateTime.class).toInstant().toEpochMilli()
                || !codec.fingerprint(binding).equals(rs.getString("binding_digest"))) { throw denied(); }
        return new AuthorizedRun(binding, rs.getLong("generation"), rs.getString("principal_id"), rs.getString("process_instance_id"),
                rs.getString("version_tools_json"), rs.getString("node_tools_json"));
    }

    private boolean lockClaim(Claim claim) {
        lockRun(claim.binding().tenantCode(), claim.binding().runId());
        var rows = jdbc.query("SELECT e.*,r.agent_version_id,r.deadline_at " + LIVE
                + " AND e.capability_hash=:hash AND e.lease_owner=:owner AND d.command_id=CAST(:command AS uuid)",
                Map.of("tenant", claim.binding().tenantCode(), "run", claim.binding().runId(), "generation", claim.generation(),
                        "hash", GraphGatewayCodec.sha256(claim.capability()), "owner", claim.workerId(), "command", claim.commandId()),
                (rs, n) -> authority(rs));
        return rows.size() == 1 && rows.getFirst().binding().equals(claim.binding());
    }
    private void clearCapability(Binding binding) {
        jdbc.update("UPDATE agent_graph_execution SET capability_hash=NULL,lease_owner=NULL,lease_expires_at=NULL WHERE tenant_code=:tenant AND run_id=:run",
                Map.of("tenant", binding.tenantCode(), "run", binding.runId()));
    }
    private void lockRun(String tenant, long run) {
        var found = jdbc.query("SELECT id FROM agent_run WHERE tenant_code=:tenant AND id=:run FOR UPDATE",
                Map.of("tenant", tenant, "run", run), (rs, n) -> rs.getLong(1));
        if (found.isEmpty()) { throw denied(); }
    }
    private <T> T atomic(Supplier<T> work) { return transaction.execute(status -> work.get()); }
    private Binding decode(String json) { return codec.read(json.getBytes(StandardCharsets.UTF_8), Binding.class); }
    private String json(Object value) { return new String(codec.write(value), StandardCharsets.UTF_8); }
    private static void lease(int seconds) { require(seconds >= 1 && seconds <= 120); }
    private static GraphGatewayException denied() { return new GraphGatewayException(AUTHORITY_DENIED); }
    private record Source(long version, String input, String principal, String process, String tools, String nodeTools, Instant deadline) { }
}

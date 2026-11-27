package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.digest;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.reference;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.AUTHORITY_DENIED;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Binding;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphLoopPolicy;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunBudgetPort.Policy;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Atomically freezes a new Run binding; joins Flowable/submission transactions when present. */
final class JdbcGraphReservationStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final GraphGatewayCodec codec;
    private final TransactionTemplate transaction;

    JdbcGraphReservationStore(NamedParameterJdbcTemplate jdbc, GraphGatewayCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.transaction =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource())));
        transaction.setTimeout(5);
    }

    private <T> T atomic(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    public Binding reserve(
            String tenant,
            long run,
            String artifact,
            int modelBudget,
            int toolBudget,
            int schemaVersion,
            Policy budget,
            GraphLoopPolicy loopPolicy) {
        Objects.requireNonNull(loopPolicy);
        Objects.requireNonNull(budget);
        require(schemaVersion == 1 || schemaVersion == 2);
        reference(tenant);
        digest(artifact);
        require(
                run > 0
                        && modelBudget > 0
                        && modelBudget <= 100
                        && toolBudget >= 0
                        && toolBudget <= 200);
        return atomic(
                () -> {
                    var policy = GraphCapacityLock.lock(jdbc);
                    lockRun(tenant, run);
                    var key = Map.of("tenant", tenant, "run", run);
                    var existing =
                            jdbc.query(
                                    "SELECT binding_json FROM agent_graph_execution WHERE"
                                            + " tenant_code=:tenant AND run_id=:run",
                                    key,
                                    (rs, n) -> decode(rs.getString(1)));
                    if (!existing.isEmpty()) {
                        Binding old = existing.getFirst();
                        require(
                                old.stateSchemaVersion() == schemaVersion
                                        && old.graphArtifactDigest().equals(artifact)
                                        && old.maxModelCalls() == modelBudget
                                        && old.maxToolCalls() == toolBudget);
                        if (schemaVersion == 2) {
                            require(budget.equals(budgetPolicy(tenant, run)));
                            var frozen =
                                    jdbc.queryForObject(
                                            "SELECT policy_json FROM agent_graph_loop_policy WHERE"
                                                    + " tenant_code=:tenant AND run_id=:run",
                                            key,
                                            String.class);
                            require(
                                    codec.fingerprint(loopPolicy)
                                            .equals(
                                                    codec.fingerprint(
                                                            codec.read(
                                                                    frozen.getBytes(
                                                                            StandardCharsets.UTF_8),
                                                                    GraphLoopPolicy.class))));
                        }
                        return old;
                    }
                    long outstanding = countOutstanding(null);
                    long tenantOutstanding = countOutstanding(tenant);
                    if (outstanding >= policy.outstanding()
                            || tenantOutstanding
                                    >= GraphCapacityLock.tenant(jdbc, policy, tenant)
                                            .tenantOutstanding()) {
                        throw new GraphGatewayException(
                                GraphGatewayException.Code.CAPACITY_EXHAUSTED);
                    }
                    var source =
                            jdbc.query(
                                    """
                                    SELECT r.*, v.tool_set_json::text AS tools, v.output_schema AS output_schema, r.input_snapshot_json::text AS input,
                                           (r.input_snapshot_json->'nodeToolSet')::text AS node_tools
                                    FROM agent_run r JOIN agent_definition_version v ON v.id=r.agent_version_id AND v.tenant_code=r.tenant_code
                                    WHERE r.tenant_code=:tenant AND r.id=:run AND r.status='QUEUED' AND r.execution_backend='NATIVE'
                                      AND r.current_attempt_id IS NULL AND r.deadline_at>clock_timestamp() AND v.status='PUBLISHED'
                                      AND NOT EXISTS (SELECT 1 FROM agent_run_attempt a WHERE a.tenant_code=r.tenant_code AND a.agent_run_id=r.id)
                                    """,
                                    key,
                                    (rs, n) ->
                                            new Source(
                                                    rs.getLong("agent_version_id"),
                                                    rs.getString("input"),
                                                    rs.getString("requested_by"),
                                                    rs.getString("process_instance_id"),
                                                    rs.getString("tools"),
                                                    rs.getString("node_tools"),
                                                    rs.getObject(
                                                                    "deadline_at",
                                                                    OffsetDateTime.class)
                                                            .toInstant(),
                                                    rs.getString("output_schema")));
                    if (source.size() != 1) {
                        throw denied();
                    }
                    Source s = source.getFirst();
                    require(s.principal() != null && !s.principal().isBlank() && s.input() != null);
                    codec.arguments(s.input());
                    String scope = tenant + ":" + run + ":";
                    var binding =
                            new Binding(
                                    tenant,
                                    run,
                                    s.version(),
                                    artifact,
                                    schemaVersion,
                                    "input:" + GraphGatewayCodec.sha256(scope + s.input()),
                                    "subject:" + GraphGatewayCodec.sha256(scope + s.principal()),
                                    s.deadline().toEpochMilli(),
                                    modelBudget,
                                    toolBudget);
                    var params =
                            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(key)
                                    .addValue("binding", json(binding))
                                    .addValue("digest", codec.fingerprint(binding))
                                    .addValue("input", s.input())
                                    .addValue("principal", s.principal())
                                    .addValue("process", s.process())
                                    .addValue("tools", s.tools())
                                    .addValue(
                                            "nodeTools",
                                            "null".equals(s.nodeTools()) ? null : s.nodeTools())
                                    .addValue("command", UUID.randomUUID());
                    jdbc.update(
                            """
                            INSERT INTO agent_graph_execution(tenant_code,run_id,binding_json,binding_digest,input_json,principal_id,
                                process_instance_id,version_tools_json,node_tools_json,business_identity)
                            SELECT :tenant,:run,:binding,:digest,:input,:principal,:process,:tools,:nodeTools,
                            """
                                    + GraphRunPersistence.IDENTITY
                                    + " FROM agent_run r WHERE r.tenant_code=:tenant AND r.id=:run",
                            params);
                    if (schemaVersion == 2) {
                        jdbc.update(
                                "INSERT INTO agent_graph_run_budget"
                                        + " VALUES(:tenant,:run,:logical,:physical,:validation)",
                                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(
                                                key)
                                        .addValue("logical", budget.logicalCalls())
                                        .addValue("physical", budget.physicalCalls())
                                        .addValue("validation", budget.validationCalls()));
                        jdbc.update(
                                "INSERT INTO agent_graph_tool_retry_policy VALUES(:tenant,:run,2)",
                                key);
                        jdbc.update(
                                "INSERT INTO agent_graph_loop_policy"
                                        + " VALUES(:tenant,:run,1,:policy)",
                                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(
                                                key)
                                        .addValue("policy", json(loopPolicy)));
                        var task =
                                io.github.illuseahashmap.agent.runtime.application.graph
                                        .GraphTaskContract.parseSchema(s.outputSchema(), codec);
                        com.fasterxml.jackson.databind.JsonNode planning =
                                s.outputSchema() == null || s.outputSchema().isBlank()
                                        ? null
                                        : codec.read(
                                                        s.outputSchema()
                                                                .getBytes(StandardCharsets.UTF_8),
                                                        com.fasterxml.jackson.databind.JsonNode
                                                                .class)
                                                .get("x-graph-planning");
                        require(planning == null || task != null);
                        if (task != null) {
                            var allowed = new java.util.HashSet<String>();
                            codec.read(
                                            s.tools().getBytes(StandardCharsets.UTF_8),
                                            com.fasterxml.jackson.databind.JsonNode.class)
                                    .forEach(t -> allowed.add(t.asText()));
                            if (s.nodeTools() != null && !"null".equals(s.nodeTools())) {
                                var node = new java.util.HashSet<String>();
                                codec.read(
                                                s.nodeTools().getBytes(StandardCharsets.UTF_8),
                                                com.fasterxml.jackson.databind.JsonNode.class)
                                        .forEach(t -> node.add(t.asText()));
                                allowed.retainAll(node);
                            }
                            require(
                                    task.requiredFacts().stream()
                                            .allMatch(f -> allowed.contains(f.tool())));
                            task.evaluate(s.input(), java.util.List.of(), codec);
                            jdbc.update(
                                    "INSERT INTO agent_graph_task_contract"
                                            + " VALUES(:tenant,:run,:contract,:digest)",
                                    new org.springframework.jdbc.core.namedparam
                                                    .MapSqlParameterSource(key)
                                            .addValue("contract", json(task))
                                            .addValue("digest", codec.fingerprint(task)));
                        }
                    }
                    if (schemaVersion == 2
                            && s.outputSchema() != null
                            && !s.outputSchema().isBlank()) {
                        var planning =
                                codec.read(
                                                s.outputSchema().getBytes(StandardCharsets.UTF_8),
                                                com.fasterxml.jackson.databind.JsonNode.class)
                                        .get("x-graph-planning");
                        if (planning != null) {
                            var planPolicy =
                                    codec.read(
                                            codec.write(planning),
                                            io.github.illuseahashmap.agent.runtime.application.graph
                                                    .GraphPlan.Policy.class);
                            jdbc.update(
                                    "INSERT INTO agent_graph_plan_policy"
                                            + " VALUES(:tenant,:run,:policy)",
                                    new org.springframework.jdbc.core.namedparam
                                                    .MapSqlParameterSource(key)
                                            .addValue("policy", json(planPolicy)));
                        }
                    }
                    if (schemaVersion == 2
                            && s.outputSchema() != null
                            && !s.outputSchema().isBlank()) {
                        var memory =
                                codec.read(
                                                s.outputSchema().getBytes(StandardCharsets.UTF_8),
                                                com.fasterxml.jackson.databind.JsonNode.class)
                                        .get("x-graph-memory");
                        if (memory != null) {
                            var memoryPolicy =
                                    codec.read(
                                            codec.write(memory),
                                            io.github.illuseahashmap.agent.runtime.application.graph
                                                    .GraphMemory.Policy.class);
                            jdbc.update(
                                    "INSERT INTO agent_graph_memory_policy"
                                            + " VALUES(:tenant,:run,:policy)",
                                    new org.springframework.jdbc.core.namedparam
                                                    .MapSqlParameterSource(key)
                                            .addValue("policy", json(memoryPolicy)));
                        }
                    }
                    if (schemaVersion == 2
                            && s.outputSchema() != null
                            && !s.outputSchema().isBlank()) {
                        var format =
                                codec.read(
                                                s.outputSchema().getBytes(StandardCharsets.UTF_8),
                                                com.fasterxml.jackson.databind.JsonNode.class)
                                        .get("x-graph-format");
                        if (format != null) {
                            var formatPolicy =
                                    codec.read(
                                            codec.write(format),
                                            io.github.illuseahashmap.agent.runtime.application.graph
                                                    .GraphFormatRepair.Policy.class);
                            var task =
                                    io.github.illuseahashmap.agent.runtime.application.graph
                                            .GraphTaskContract.parseSchema(s.outputSchema(), codec);
                            require(task != null && task.allowCorrection());
                            jdbc.update(
                                    "INSERT INTO agent_graph_format_policy"
                                            + " VALUES(:tenant,:run,:policy)",
                                    new org.springframework.jdbc.core.namedparam
                                                    .MapSqlParameterSource(key)
                                            .addValue("policy", json(formatPolicy)));
                        }
                    }
                    if (schemaVersion == 2
                            && s.outputSchema() != null
                            && !s.outputSchema().isBlank()) {
                        var route =
                                codec.read(
                                                s.outputSchema().getBytes(StandardCharsets.UTF_8),
                                                com.fasterxml.jackson.databind.JsonNode.class)
                                        .get("x-graph-routing");
                        if (route != null) {
                            var routePolicy =
                                    codec.read(
                                            codec.write(route),
                                            io.github.illuseahashmap.agent.runtime.application.graph
                                                    .GraphRoute.Policy.class);
                            jdbc.update(
                                    "INSERT INTO agent_graph_route_policy"
                                            + " VALUES(:tenant,:run,:policy)",
                                    new org.springframework.jdbc.core.namedparam
                                                    .MapSqlParameterSource(key)
                                            .addValue("policy", json(routePolicy)));
                        }
                    }
                    jdbc.update(
                            "INSERT INTO agent_graph_dispatch(tenant_code,run_id,command_id,status)"
                                    + " VALUES (:tenant,:run,:command,'READY')",
                            params);
                    jdbc.update(
                            "UPDATE agent_run SET execution_backend='LANGGRAPH' WHERE"
                                    + " tenant_code=:tenant AND id=:run",
                            key);
                    jdbc.update(
                            "INSERT INTO agent_graph_tenant_turn(tenant_code) VALUES(:tenant) ON"
                                    + " CONFLICT DO NOTHING",
                            key);
                    return binding;
                });
    }

    private void lockRun(String tenant, long run) {
        var found =
                jdbc.query(
                        "SELECT id FROM agent_run WHERE tenant_code=:tenant AND id=:run FOR UPDATE",
                        Map.of("tenant", tenant, "run", run),
                        (rs, n) -> rs.getLong(1));
        if (found.isEmpty()) {
            throw denied();
        }
    }

    private long countOutstanding(String tenant) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM agent_graph_execution e JOIN agent_run r ON r.tenant_code=e.tenant_code AND r.id=e.run_id
                WHERE r.status IN ('QUEUED','RUNNING','WAITING','PAUSED')
                    AND (:tenant IS NULL OR e.tenant_code=:tenant)
                """,
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("tenant", tenant, java.sql.Types.VARCHAR),
                Long.class);
    }

    private Policy budgetPolicy(String tenant, long run) {
        return jdbc.queryForObject(
                "SELECT * FROM agent_graph_run_budget WHERE tenant_code=:tenant AND run_id=:run",
                Map.of("tenant", tenant, "run", run),
                (rs, n) ->
                        new Policy(
                                rs.getInt("logical_limit"),
                                rs.getInt("physical_limit"),
                                rs.getInt("validation_limit")));
    }

    private Binding decode(String json) {
        return codec.read(json.getBytes(StandardCharsets.UTF_8), Binding.class);
    }

    private String json(Object value) {
        return new String(codec.write(value), StandardCharsets.UTF_8);
    }

    private static GraphGatewayException denied() {
        return new GraphGatewayException(AUTHORITY_DENIED);
    }

    private record Source(
            long version,
            String input,
            String principal,
            String process,
            String tools,
            String nodeTools,
            Instant deadline,
            String outputSchema) {}
}

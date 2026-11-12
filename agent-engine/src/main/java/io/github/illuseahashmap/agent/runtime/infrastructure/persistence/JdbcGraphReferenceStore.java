package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException;
import io.github.illuseahashmap.agent.runtime.application.port.GraphReferencePort;
import io.github.illuseahashmap.agent.runtime.application.port.GraphReferenceWriterPort;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.reference;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.REFERENCE_DENIED;

/** Immutable references over the existing tool ledger, never a second tool execution authority. */
public final class JdbcGraphReferenceStore implements GraphReferencePort, GraphReferenceWriterPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcGraphExecutionStore executions;
    private final GraphGatewayCodec codec;

    public JdbcGraphReferenceStore(NamedParameterJdbcTemplate jdbc, JdbcGraphExecutionStore executions, GraphGatewayCodec codec) {
        executions.requireSameDataSource(jdbc);
        this.jdbc = jdbc;
        this.executions = executions;
        this.codec = codec;
    }

    @Override public String recordArguments(AuthorizedRun run, String operation, String tool, String json) {
        reference(tool);
        require(operation != null && operation.matches("tool:[1-9][0-9]{0,2}")
                && Integer.parseInt(operation.substring(5)) <= run.binding().maxToolCalls());
        require(json != null);
        codec.arguments(json);
        return executions.withCurrentRun(run, () -> recordArgumentsInTransaction(run, operation, tool, json));
    }

    /** Only called from the cognition receipt transaction that already holds the Run fence. */
    String recordArgumentsInTransaction(AuthorizedRun run, String operation, String tool, String json) {
        reference(tool);
        codec.arguments(json);
        return insertOrCompare(run, "ARGUMENTS", operation, tool, json);
    }

    @Override public Arguments arguments(AuthorizedRun run, Invocation request) {
        require("TOOL".equals(request.kind()));
        return executions.withCurrentRun(run, () -> readArguments(run, request));
    }

    private Arguments readArguments(AuthorizedRun run, Invocation request) {
        var rows = jdbc.query("""
                    SELECT binding_digest,operation_id,tool_code,content_digest,body FROM agent_graph_reference
                    WHERE tenant_code=:tenant AND run_id=:run AND reference=:ref AND kind='ARGUMENTS'
                      AND binding_digest=:binding AND operation_id=:op AND tool_code=:tool AND content_digest=:digest
                    """, Map.of("tenant", run.binding().tenantCode(), "run", run.binding().runId(), "ref", request.tool().argumentsRef(),
                    "binding", codec.fingerprint(run.binding()), "op", request.operationId(), "tool", request.tool().toolCode(),
                    "digest", request.tool().argumentsDigest()), (rs, n) -> new Arguments(rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4), rs.getString(5)));
        if (rows.size() != 1) { throw denied(); }
        return rows.getFirst();
    }

    @Override public String recordReceipt(AuthorizedRun run, Invocation request, String key, String outputDigest) {
        require("TOOL".equals(request.kind()));
        String expectedKey = run.binding().runId() + ":" + request.operationId() + ":" + request.tool().toolCode();
        if (!expectedKey.equals(key)) { throw denied(); }
        return executions.withCurrentRun(run, () -> {
            readArguments(run, request);
            // Receipt references must resolve to an already successful platform invocation, not arbitrary output.
            var ledger = jdbc.query("""
                    SELECT id,output_snapshot FROM agent_tool_execution_audit
                    WHERE tenant_code=:tenant AND tool_code=:tool AND idempotency_key=:key AND status='SUCCEEDED'
                    """, Map.of("tenant", run.binding().tenantCode(), "tool", request.tool().toolCode(), "key", key),
                    (rs, n) -> new Ledger(rs.getLong(1), rs.getString(2)));
            if (ledger.size() != 1 || ledger.getFirst().output() == null
                    || !GraphGatewayCodec.sha256(ledger.getFirst().output()).equals(outputDigest)) { throw denied(); }
            // Map.of iteration order can change after JVM restart. Persist canonical key order for byte-exact replay.
            String body = new String(codec.write(new TreeMap<>(Map.of("audit_id", ledger.getFirst().id(), "idempotency_key", key,
                    "output_digest", outputDigest, "request_digest", codec.fingerprint(request)))), StandardCharsets.UTF_8);
            return insertOrCompare(run, "TOOL_RECEIPT", request.operationId(), request.tool().toolCode(), body);
        });
    }

    private String insertOrCompare(AuthorizedRun run, String kind, String operation, String tool, String body) {
        var params = Map.of("tenant", run.binding().tenantCode(), "run", run.binding().runId(),
                "ref", "graph:" + UUID.randomUUID(), "binding", codec.fingerprint(run.binding()), "kind", kind,
                "op", operation, "tool", tool, "digest", GraphGatewayCodec.sha256(body), "body", body);
        jdbc.update("""
                INSERT INTO agent_graph_reference(tenant_code,run_id,reference,binding_digest,kind,operation_id,tool_code,content_digest,body)
                VALUES(:tenant,:run,:ref,:binding,:kind,:op,:tool,:digest,:body)
                ON CONFLICT(tenant_code,run_id,kind,operation_id) DO NOTHING
                """, params);
        var matches = jdbc.query("""
                SELECT reference FROM agent_graph_reference WHERE tenant_code=:tenant AND run_id=:run AND kind=:kind AND operation_id=:op
                  AND binding_digest=:binding AND tool_code=:tool AND content_digest=:digest AND body=:body
                """, params, (rs, n) -> rs.getString(1));
        if (matches.size() != 1) { throw denied(); }
        return matches.getFirst();
    }
    private static GraphGatewayException denied() { return new GraphGatewayException(REFERENCE_DENIED); }
    private record Ledger(long id, String output) { }
}

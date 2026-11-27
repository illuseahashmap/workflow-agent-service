package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Decision;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Receipt;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.ToolIntent;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphMemory;
import io.github.illuseahashmap.agent.runtime.application.port.GraphCognitionPort.ToolFact;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Immutable provenance and local reads only. Callers hold the Run lock and reauthorize original
 * evidence.
 */
final class JdbcGraphMemoryStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final GraphGatewayCodec codec;

    JdbcGraphMemoryStore(NamedParameterJdbcTemplate jdbc, GraphGatewayCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    private MapSqlParameterSource key(AuthorizedRun run) {
        return new MapSqlParameterSource("tenant", run.binding().tenantCode())
                .addValue("run", run.binding().runId());
    }

    GraphMemory.Policy policy(AuthorizedRun run) {
        var rows =
                jdbc.query(
                        "SELECT policy_json FROM agent_graph_memory_policy WHERE"
                                + " tenant_code=:tenant AND run_id=:run",
                        key(run),
                        (rs, n) -> read(rs.getString(1), GraphMemory.Policy.class));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    GraphMemory.Context context(AuthorizedRun run, Invocation request, List<ToolFact> facts) {
        var policy = policy(run);
        var selected = request.inputRefs().stream().filter(GraphMemory::contextReference).toList();
        if (policy == null) {
            require(
                    selected.isEmpty()
                            && !request.operationId().startsWith("summary:")
                            && !"READ_RESULT".equals(request.kind()));
            return null;
        }
        GraphMemory.Summary summary = null;
        GraphMemory.Read segment = null;
        var sources = facts.stream().map(ToolFact::receiptRef).toList();
        for (var ref : selected) {
            if (ref.startsWith("memory:")) {
                require(summary == null);
                var rows =
                        jdbc.query(
                                "SELECT * FROM agent_graph_memory WHERE tenant_code=:tenant AND"
                                        + " run_id=:run AND reference=:ref",
                                key(run).addValue("ref", ref),
                                (rs, n) -> {
                                    require(
                                            request.inputRefs()
                                                    .contains(rs.getString("receipt_ref")));
                                    return new GraphMemory.Summary(
                                            ref,
                                            rs.getString("notes"),
                                            List.of(
                                                    read(
                                                            rs.getString("sources_json"),
                                                            String[].class)));
                                });
                require(rows.size() == 1 && sources.containsAll(rows.getFirst().sources()));
                summary = rows.getFirst();
            } else {
                require(segment == null);
                var rows =
                        jdbc.query(
                                "SELECT r.*,i.source_ref,i.pointer FROM agent_graph_read_result r"
                                        + " JOIN agent_graph_read_intent i"
                                        + " USING(tenant_code,run_id,intent_ref) WHERE"
                                        + " r.tenant_code=:tenant AND r.run_id=:run AND"
                                        + " r.reference=:ref",
                                key(run).addValue("ref", ref),
                                (rs, n) -> {
                                    require(
                                            request.inputRefs()
                                                    .contains(
                                                            read(
                                                                            rs.getString(
                                                                                    "receipt_json"),
                                                                            Receipt.class)
                                                                    .receiptRef()));
                                    return new GraphMemory.Read(
                                            ref,
                                            rs.getString("source_ref"),
                                            rs.getString("pointer"),
                                            rs.getString("status"),
                                            rs.getString("content"));
                                });
                require(rows.size() == 1 && sources.contains(rows.getFirst().source()));
                segment = rows.getFirst();
            }
        }
        if (request.operationId().startsWith("summary:")) {
            require(
                    "MODEL".equals(request.kind())
                            && request.operationId()
                                    .equals(
                                            "summary:"
                                                    + codec.fingerprint(
                                                            List.of(
                                                                    codec.fingerprint(
                                                                            run.binding()),
                                                                    request.inputRefs()))));
        }
        return new GraphMemory.Context(policy, summary, segment);
    }

    void saveSummary(
            AuthorizedRun run,
            Invocation request,
            GraphMemory.Context context,
            List<ToolFact> facts,
            String notes,
            Receipt receipt) {
        require(
                context != null
                        && !context.delta(facts).isEmpty()
                        && notes != null
                        && !notes.isBlank()
                        && notes.length() <= context.policy().summaryCharacters());
        jdbc.update(
                "INSERT INTO agent_graph_memory"
                        + " VALUES(:tenant,:run,:ref,:op,:parent,:sources,:notes,:receipt)",
                key(run).addValue("ref", receipt.decision().candidateRef())
                        .addValue("op", request.operationId())
                        .addValue(
                                "parent",
                                context.summary() == null ? null : context.summary().reference())
                        .addValue(
                                "sources", text(facts.stream().map(ToolFact::receiptRef).toList()))
                        .addValue("notes", notes)
                        .addValue("receipt", receipt.receiptRef()));
    }

    void saveReadIntent(
            AuthorizedRun run,
            Invocation request,
            ToolIntent intent,
            GraphMemory.ReadAction action) {
        jdbc.update(
                "INSERT INTO agent_graph_read_intent"
                        + " VALUES(:tenant,:run,:intent,:model,:source,:pointer)",
                key(run).addValue("intent", intent.argumentsRef())
                        .addValue("model", request.operationId())
                        .addValue("source", action.reference())
                        .addValue("pointer", action.pointer()));
    }

    String readReceipt(AuthorizedRun run, ToolIntent intent) {
        var rows =
                jdbc.query(
                        "SELECT receipt_json FROM agent_graph_read_result WHERE tenant_code=:tenant"
                                + " AND run_id=:run AND intent_ref=:intent",
                        key(run).addValue("intent", intent.argumentsRef()),
                        (rs, n) -> read(rs.getString(1), Receipt.class));
        require(rows.size() == 1);
        return rows.getFirst().receiptRef();
    }

    Receipt readResult(AuthorizedRun run, Invocation request, List<ToolFact> facts) {
        require(
                policy(run) != null
                        && request.tool() != null
                        && GraphMemory.READ_TOOL.equals(request.tool().toolCode())
                        && request.operationId().equals("read:" + request.tool().argumentsRef()));
        var params = key(run).addValue("intent", request.tool().argumentsRef());
        var intents =
                jdbc.query(
                        "SELECT source_ref,pointer FROM agent_graph_read_intent WHERE"
                                + " tenant_code=:tenant AND run_id=:run AND intent_ref=:intent",
                        params,
                        (rs, n) ->
                                new GraphMemory.ReadAction(
                                        "READ_RESULT", rs.getString(1), rs.getString(2)));
        require(
                intents.size() == 1
                        && GraphGatewayCodec.sha256(text(intents.getFirst()))
                                .equals(request.tool().argumentsDigest()));
        var action = intents.getFirst();
        var original =
                facts.stream()
                        .filter(f -> f.receiptRef().equals(action.reference()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new GraphGatewayException(
                                                GraphGatewayException.Code.REFERENCE_DENIED));
        var old =
                jdbc.query(
                        "SELECT receipt_json FROM agent_graph_read_result WHERE tenant_code=:tenant"
                                + " AND run_id=:run AND intent_ref=:intent",
                        params,
                        (rs, n) -> read(rs.getString(1), Receipt.class));
        if (!old.isEmpty()) {
            require(old.getFirst().requestDigest().equals(codec.fingerprint(request)));
            return old.getFirst();
        }
        String content = original.output();
        String status = "OK";
        if (!action.pointer().isEmpty()) {
            try {
                var part =
                        read(original.output(), com.fasterxml.jackson.databind.JsonNode.class)
                                .at(action.pointer());
                if (part.isMissingNode()) {
                    status = "MISSING_FIELD";
                    content = "";
                } else {
                    content = part.isTextual() ? part.textValue() : part.toString();
                }
            } catch (GraphGatewayException invalid) {
                status = "NOT_JSON";
                content = "";
            }
        }
        if (content.length() > 8192) {
            status = "TOO_LARGE";
            content = "";
        }
        String ref = "read-context:" + UUID.randomUUID();
        var receipt =
                new Receipt(
                        codec.fingerprint(run.binding()),
                        request.operationId(),
                        codec.fingerprint(request),
                        "read-receipt:" + UUID.randomUUID(),
                        new Decision(List.of(), ref),
                        null);
        jdbc.update(
                "INSERT INTO agent_graph_read_result"
                        + " VALUES(:tenant,:run,:op,:intent,:ref,:status,:content,:receipt)",
                params.addValue("op", request.operationId())
                        .addValue("ref", ref)
                        .addValue("status", status)
                        .addValue("content", content)
                        .addValue("receipt", text(receipt)));
        return receipt;
    }

    private String text(Object value) {
        return new String(codec.write(value), StandardCharsets.UTF_8);
    }

    private <T> T read(String text, Class<T> type) {
        return codec.read(text.getBytes(StandardCharsets.UTF_8), type);
    }
}

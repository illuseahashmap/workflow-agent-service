package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Receipt;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphLoopDetector;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphLoopDetector.Observation;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphLoopPolicy;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Caller owns the Run lock and transaction. No graph cursor and no remote calls. */
final class JdbcGraphLoopStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcGraphExecutionStore executions;
    private final GraphGatewayCodec codec;

    JdbcGraphLoopStore(
            NamedParameterJdbcTemplate jdbc,
            JdbcGraphExecutionStore executions,
            GraphGatewayCodec codec) {
        this.jdbc = jdbc;
        this.executions = executions;
        this.codec = codec;
    }

    boolean checkInTransaction(AuthorizedRun run, Invocation request) {
        if (!"TOOL".equals(request.kind())) {
            return false;
        }
        var key =
                new MapSqlParameterSource("tenant", run.binding().tenantCode())
                        .addValue("run", run.binding().runId())
                        .addValue("op", request.operationId())
                        .addValue("digest", codec.fingerprint(request));
        var old =
                jdbc.query(
                        "SELECT request_digest,pattern FROM agent_graph_loop_verdict WHERE"
                            + " tenant_code=:tenant AND run_id=:run AND operation_id=:op",
                        key,
                        (rs, n) -> Map.entry(rs.getString(1), rs.getString(2)));
        if (!old.isEmpty()) {
            require(old.getFirst().getKey().equals(codec.fingerprint(request)));
            return !"ALLOW".equals(old.getFirst().getValue());
        }
        var policy =
                jdbc.queryForObject(
                        "SELECT policy_json FROM agent_graph_loop_policy WHERE tenant_code=:tenant"
                            + " AND run_id=:run AND policy_version=1",
                        key,
                        (rs, n) -> read(rs.getString(1), GraphLoopPolicy.class));
        var models =
                jdbc.query(
                        """
                        SELECT ordinal,receipt_json FROM agent_graph_v2_model_call
                        WHERE tenant_code=:tenant AND run_id=:run AND receipt_json IS NOT NULL ORDER BY ordinal
                        """,
                        key,
                        (rs, n) -> new Turn(rs.getInt(1), read(rs.getString(2), Receipt.class)));
        var parents =
                models.stream()
                        .filter(t -> t.receipt().decision().tools().contains(request.tool()))
                        .toList();
        require(parents.size() == 1 && request.operationId().equals(request.tool().argumentsRef()));
        var parent = parents.getFirst();
        // A successful cached operation creates no new detection event, including after upgrading
        // old v2 history.
        if (jdbc.queryForObject(
                        """
                        SELECT count(*) FROM agent_graph_reference WHERE tenant_code=:tenant AND run_id=:run
                        AND operation_id=:op AND kind='TOOL_RECEIPT'
                        """,
                        key,
                        Integer.class)
                != 0) {
            return false;
        }
        var references = new JdbcGraphReferenceStore(jdbc, executions, codec);
        var detector = new GraphLoopDetector(codec);
        String binding = codec.fingerprint(run.binding());
        var proposed = new ArrayList<String>();
        for (var tool : parent.receipt().decision().tools()) {
            var args =
                    references.readArguments(
                            run, new Invocation("TOOL", tool.argumentsRef(), List.of(), tool));
            proposed.add(detector.action(binding, tool.toolCode(), args.json(), policy));
        }
        String action = codec.fingerprint(proposed.stream().sorted().toList());
        var history = new ArrayList<Observation>();
        var toolEvidence = new java.util.LinkedHashMap<String, List<String>>();
        var facts = new JdbcGraphV2CognitionStore(jdbc, executions, codec);
        int superseded = new JdbcGraphCorrectionStore(jdbc, codec).supersededOrdinal(run);
        for (var model : models) {
            if (model.ordinal() == superseded) {
                continue;
            }
            if (model.ordinal() >= parent.ordinal()) {
                break;
            }
            var tools = model.receipt().decision().tools();
            if (tools.isEmpty()
                    || tools.stream()
                            .allMatch(
                                    t ->
                                            io.github.illuseahashmap.agent.runtime.application.graph
                                                    .GraphMemory.READ_TOOL
                                                    .equals(t.toolCode()))) {
                continue;
            } // Answers/summaries do not reset observable tool history.
            var actions = new ArrayList<String>();
            var results = new ArrayList<String>();
            var refs = new ArrayList<String>();
            for (var tool : tools) {
                var fact =
                        facts.fact(
                                run,
                                tool); // Trusted successful audit output, bound args and immutable
                                       // receipt.
                String priorAction =
                        detector.action(binding, tool.toolCode(), fact.argumentsJson(), policy);
                actions.add(priorAction);
                results.add(
                        codec.fingerprint(
                                List.of(
                                        priorAction,
                                        detector.result(tool.toolCode(), fact.output(), policy))));
                refs.add(fact.receiptRef());
            }
            // One causal observation per committed model decision. Sibling arrival/order is not a
            // sequence.
            history.add(
                    new Observation(
                            codec.fingerprint(actions.stream().sorted().toList()),
                            codec.fingerprint(results.stream().sorted().toList()),
                            model.receipt().receiptRef(),
                            model.ordinal(),
                            true));
            toolEvidence.put(model.receipt().receiptRef(), List.copyOf(refs));
        }
        var verdict = detector.evaluate(action, parent.ordinal(), true, history);
        var window = history.subList(Math.max(0, history.size() - 8), history.size());
        var windowRefs =
                window.stream()
                        .map(Observation::receiptRef)
                        .collect(java.util.stream.Collectors.toSet());
        toolEvidence.keySet().retainAll(windowRefs);
        var evidence =
                Map.of(
                        "source_model_receipt",
                        parent.receipt().receiptRef(),
                        "matched_refs",
                        verdict.evidenceRefs(),
                        "observations",
                        window,
                        "tool_receipts",
                        toolEvidence);
        jdbc.update(
                """
                INSERT INTO agent_graph_loop_verdict(tenant_code,run_id,operation_id,request_digest,policy_version,action_digest,evidence_json,pattern)
                VALUES(:tenant,:run,:op,:digest,1,:action,:evidence,:pattern)
                """,
                key.addValue("action", action)
                        .addValue(
                                "evidence",
                                new String(codec.write(evidence), StandardCharsets.UTF_8))
                        .addValue("pattern", verdict.pattern()));
        return verdict.stopped();
    }

    private <T> T read(String json, Class<T> type) {
        return codec.read(json.getBytes(StandardCharsets.UTF_8), type);
    }

    private record Turn(int ordinal, Receipt receipt) {}
}

package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Decision;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Receipt;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.ToolIntent;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException;
import io.github.illuseahashmap.agent.runtime.application.port.GraphCognitionPort;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.BINDING_MISMATCH;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.MODEL_IN_PROGRESS;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.MODEL_BUDGET_EXHAUSTED;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.REFERENCE_DENIED;

/** Durable raw responses precede interpretation. No network calls and no graph scheduling in transactions. */
public final class JdbcGraphCognitionStore implements GraphCognitionPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcGraphExecutionStore executions;
    private final JdbcGraphReferenceStore references;
    private final GraphGatewayCodec codec;

    public JdbcGraphCognitionStore(NamedParameterJdbcTemplate jdbc, JdbcGraphExecutionStore executions, GraphGatewayCodec codec) {
        executions.requireSameDataSource(jdbc);
        this.jdbc = jdbc;
        this.executions = executions;
        this.references = new JdbcGraphReferenceStore(jdbc, executions, codec);
        this.codec = codec;
    }

    @Override public History history(AuthorizedRun run, Invocation request) {
        return executions.withCurrentRun(run, () -> historyLocked(run, request));
    }

    @Override public boolean hasResponse(AuthorizedRun run, Invocation request) {
        return executions.withCurrentRun(run, () -> {
            historyLocked(run, request);
            return jdbc.queryForObject("""
                    SELECT count(*) FROM agent_graph_model_call WHERE tenant_code=:tenant AND run_id=:run
                        AND ordinal=:ordinal AND request_digest=:digest AND response_json IS NOT NULL
                    """, key(run).addValue("ordinal", ordinal(run, request)).addValue("digest", codec.fingerprint(request)), Long.class) == 1;
        });
    }

    @Override public ModelClaim beginModel(AuthorizedRun run, Invocation request, String configurationDigest) {
        int ordinal = ordinal(run, request);
        return executions.withCurrentRun(run, () -> {
            historyLocked(run, request);
            var params = key(run).addValue("ordinal", ordinal).addValue("request", codec.fingerprint(request))
                    .addValue("configuration", configurationDigest).addValue("generation", run.generation()).addValue("ticket", UUID.randomUUID())
                    .addValue("candidate", "candidate:" + UUID.randomUUID());
            var old = calls(run).stream().filter(c -> c.ordinal() == ordinal).findFirst().orElse(null);
            if (old != null) {
                if (!old.request().equals(codec.fingerprint(request))) { throw mismatch(); }
                if (old.response() != null) { return new ModelClaim(null, old.response(), old.receipt()); }
                if (!old.configuration().equals(configurationDigest)) { throw mismatch(); }
                if (old.generation() >= run.generation()) { throw new GraphGatewayException(MODEL_IN_PROGRESS); }
            }
            Integer physical = jdbc.queryForObject("SELECT COALESCE(sum(physical_calls),0) FROM agent_graph_model_call WHERE tenant_code=:tenant AND run_id=:run",
                    key(run), Integer.class);
            if (physical == null || physical >= run.binding().maxModelCalls()) { throw new GraphGatewayException(MODEL_BUDGET_EXHAUSTED); }
            if (old == null) {
                jdbc.update("""
                        INSERT INTO agent_graph_model_call(tenant_code,run_id,ordinal,request_digest,configuration_digest,generation,ticket,candidate_ref)
                        VALUES(:tenant,:run,:ordinal,:request,:configuration,:generation,:ticket,:candidate)
                        """, params);
            } else {
                jdbc.update("""
                        UPDATE agent_graph_model_call SET generation=:generation,ticket=:ticket,physical_calls=physical_calls+1
                        WHERE tenant_code=:tenant AND run_id=:run AND ordinal=:ordinal AND response_json IS NULL
                        """, params);
            }
            return new ModelClaim(params.getValue("ticket").toString(), null, null);
        });
    }

    @Override public void saveResponse(AuthorizedRun run, Invocation request, String ticket, ModelProviderResponse response) {
        require(response != null);
        String body = json(response);
        // A committed response must be readable with the same bounded codec after process restart.
        read(body, ModelProviderResponse.class);
        executions.withCurrentRun(run, () -> {
            int changed = jdbc.update("""
                    UPDATE agent_graph_model_call SET response_json=:body WHERE tenant_code=:tenant AND run_id=:run AND ordinal=:ordinal
                        AND request_digest=:request AND generation=:generation AND ticket=CAST(:ticket AS uuid) AND response_json IS NULL
                    """, key(run).addValue("body", body).addValue("ordinal", ordinal(run, request)).addValue("request", codec.fingerprint(request))
                    .addValue("generation", run.generation()).addValue("ticket", ticket));
            if (changed != 1) { throw mismatch(); }
            return null;
        });
    }

    @Override public Receipt finishModel(AuthorizedRun run, Invocation request, String tool, String argumentsJson) {
        return executions.withCurrentRun(run, () -> {
            History history = historyLocked(run, request);
            Call call = calls(run).stream().filter(c -> c.ordinal() == ordinal(run, request)).findFirst().orElseThrow(JdbcGraphCognitionStore::mismatch);
            if (call.response() == null || !call.request().equals(codec.fingerprint(request))) { throw mismatch(); }
            Decision decision;
            if (tool == null) {
                decision = new Decision(List.of(), call.candidate());
            } else {
                int number = history.tools().size() + 1;
                require(number <= run.binding().maxToolCalls());
                String ref = references.recordArgumentsInTransaction(run, "tool:" + number, tool, argumentsJson);
                decision = new Decision(List.of(new ToolIntent(tool, ref, GraphGatewayCodec.sha256(argumentsJson))), null);
            }
            if (call.receipt() != null) {
                if (!call.receipt().decision().equals(decision)) { throw mismatch(); }
                return call.receipt();
            }
            var receipt = new Receipt(codec.fingerprint(run.binding()), request.operationId(), codec.fingerprint(request),
                    "model-receipt:" + UUID.randomUUID(), decision, null);
            jdbc.update("UPDATE agent_graph_model_call SET receipt_json=:body WHERE tenant_code=:tenant AND run_id=:run AND ordinal=:ordinal",
                    key(run).addValue("body", json(receipt)).addValue("ordinal", call.ordinal()));
            return receipt;
        });
    }

    @Override public Receipt finishValidation(AuthorizedRun run, Invocation request, boolean accepted, String reasonCode) {
        require("VALIDATE".equals(request.kind()));
        require(reasonCode != null && reasonCode.matches("[A-Z0-9_]{1,64}"));
        return executions.withCurrentRun(run, () -> {
            historyLocked(run, request);
            var old = jdbc.query("SELECT request_digest,receipt_json FROM agent_graph_validation WHERE tenant_code=:tenant AND run_id=:run",
                    key(run), (rs, n) -> {
                        if (!rs.getString(1).equals(codec.fingerprint(request))) { throw mismatch(); }
                        return read(rs.getString(2), Receipt.class);
                    });
            if (!old.isEmpty()) {
                // Current authorization/evidence is checked by the caller before replay; a rejection cannot be repaired to success.
                if (old.getFirst().accepted() != accepted) { throw mismatch(); }
                return old.getFirst();
            }
            var receipt = new Receipt(codec.fingerprint(run.binding()), request.operationId(), codec.fingerprint(request),
                    "validation:" + UUID.randomUUID(), null, accepted);
            jdbc.update("""
                    INSERT INTO agent_graph_validation(tenant_code,run_id,request_digest,receipt_json,reason_code)
                    VALUES(:tenant,:run,:request,:receipt,:reason)
                    """, key(run).addValue("request", codec.fingerprint(request)).addValue("receipt", json(receipt)).addValue("reason", reasonCode));
            return receipt;
        });
    }

    /** Only the completion repository calls this while holding the same Run row lock. */
    io.github.illuseahashmap.agent.runtime.application.port.GraphCompletionPort.Evidence completionEvidence(
            AuthorizedRun run, io.github.illuseahashmap.agent.runtime.application.port.GraphDispatchPort.Observation observation) {
        var refs = new ArrayList<String>();
        String candidate = null;
        int toolNumber = 0;
        for (Call call : calls(run)) {
            if (call.receipt() == null || candidate != null
                    || !codec.fingerprint(run.binding()).equals(call.receipt().bindingDigest())
                    || !call.request().equals(call.receipt().requestDigest())) { throw mismatch(); }
            refs.add(call.receipt().receiptRef());
            if (call.receipt().decision().candidateRef() != null) {
                candidate = call.receipt().decision().candidateRef();
            } else {
                require(call.receipt().decision().tools().size() == 1);
                refs.add(fact(run, call.receipt().decision().tools().getFirst(), ++toolNumber).receiptRef());
            }
        }
        if (candidate == null || !candidate.equals(observation.candidateRef())) { throw mismatch(); }
        refs.addFirst(candidate);
        var invocation = new Invocation("VALIDATE", "validate:1", refs, null);
        var history = historyLocked(run, invocation);
        var evidence = jdbc.query("SELECT request_digest,receipt_json,reason_code FROM agent_graph_validation WHERE tenant_code=:tenant AND run_id=:run",
                key(run), (rs, n) -> {
                    Receipt receipt = read(rs.getString("receipt_json"), Receipt.class);
                    if (!codec.fingerprint(invocation).equals(rs.getString("request_digest"))
                            || !codec.fingerprint(invocation).equals(receipt.requestDigest())
                            || !codec.fingerprint(run.binding()).equals(receipt.bindingDigest())
                            || !"validate:1".equals(receipt.operationId()) || receipt.accepted() == null
                            || !observation.validationReceiptRef().equals(receipt.receiptRef())
                            || !(receipt.accepted() ? "CANDIDATE_READY" : "REJECTED").equals(observation.status())) { throw mismatch(); }
                    return new io.github.illuseahashmap.agent.runtime.application.port.GraphCompletionPort.Evidence(
                            run, history, receipt.accepted(), rs.getString("reason_code"));
                });
        if (evidence.size() != 1) { throw mismatch(); }
        return evidence.getFirst();
    }

    private History historyLocked(AuthorizedRun run, Invocation request) {
        boolean model = "MODEL".equals(request.kind());
        require(model || ("VALIDATE".equals(request.kind()) && "validate:1".equals(request.operationId())));
        int current = model ? ordinal(run, request) : Integer.MAX_VALUE;
        var previous = calls(run).stream().filter(c -> c.ordinal() < current).toList();
        if (model && previous.size() != current - 1) { throw mismatch(); }
        var refs = new ArrayList<String>();
        var facts = new ArrayList<ToolFact>();
        ModelProviderResponse candidate = null;
        String candidateRef = null;
        int expectedOrdinal = 1;
        for (Call call : previous) {
            if (call.ordinal() != expectedOrdinal++ || call.receipt() == null || candidate != null) { throw mismatch(); }
            refs.add(call.receipt().receiptRef());
            Decision decision = call.receipt().decision();
            if (decision.candidateRef() != null) {
                candidate = call.response();
                candidateRef = decision.candidateRef();
            } else {
                require(decision.tools().size() == 1);
                ToolFact fact = fact(run, decision.tools().getFirst(), facts.size() + 1);
                facts.add(fact);
                refs.add(fact.receiptRef());
            }
        }
        if (model && candidate != null || !model && candidate == null) { throw mismatch(); }
        refs.addFirst(model ? run.binding().inputRef() : candidateRef);
        if (!refs.equals(request.inputRefs())) { throw new GraphGatewayException(REFERENCE_DENIED); }
        String input = jdbc.queryForObject("SELECT input_json FROM agent_graph_execution WHERE tenant_code=:tenant AND run_id=:run", key(run), String.class);
        return new History(input, facts, candidate);
    }

    private ToolFact fact(AuthorizedRun run, ToolIntent tool, int number) {
        var params = key(run).addValue("op", "tool:" + number).addValue("tool", tool.toolCode())
                .addValue("ref", tool.argumentsRef()).addValue("digest", tool.argumentsDigest());
        var args = jdbc.query("""
                SELECT body FROM agent_graph_reference WHERE tenant_code=:tenant AND run_id=:run AND kind='ARGUMENTS'
                  AND operation_id=:op AND tool_code=:tool AND reference=:ref AND content_digest=:digest
                """, params, (rs, n) -> rs.getString(1));
        var receipts = jdbc.query("""
                SELECT reference,body FROM agent_graph_reference WHERE tenant_code=:tenant AND run_id=:run AND kind='TOOL_RECEIPT'
                  AND operation_id=:op AND tool_code=:tool
                """, params, (rs, n) -> Map.entry(rs.getString(1), read(rs.getString(2), JsonNode.class)));
        if (args.size() != 1 || receipts.size() != 1) { throw new GraphGatewayException(REFERENCE_DENIED); }
        var body = receipts.getFirst().getValue();
        var outputs = jdbc.query("""
                SELECT output_snapshot FROM agent_tool_execution_audit WHERE tenant_code=:tenant AND tool_code=:tool AND status='SUCCEEDED'
                  AND id=:audit AND idempotency_key=:key
                """, params.addValue("audit", body.path("audit_id").asLong())
                .addValue("key", run.binding().runId() + ":tool:" + number + ":" + tool.toolCode()), (rs, n) -> rs.getString(1));
        if (outputs.size() != 1 || outputs.getFirst() == null
                || !GraphGatewayCodec.sha256(outputs.getFirst()).equals(body.path("output_digest").asText())) {
            throw new GraphGatewayException(REFERENCE_DENIED);
        }
        return new ToolFact(receipts.getFirst().getKey(), tool.toolCode(), args.getFirst(), outputs.getFirst(), body.path("output_digest").asText());
    }

    private List<Call> calls(AuthorizedRun run) {
        return jdbc.query("SELECT * FROM agent_graph_model_call WHERE tenant_code=:tenant AND run_id=:run ORDER BY ordinal",
                key(run), (rs, n) -> new Call(rs.getInt("ordinal"), rs.getString("request_digest"), rs.getString("configuration_digest"),
                        rs.getLong("generation"), readNullable(rs.getString("response_json"), ModelProviderResponse.class),
                        readNullable(rs.getString("receipt_json"), Receipt.class), rs.getString("candidate_ref")));
    }
    private int ordinal(AuthorizedRun run, Invocation request) {
        require("MODEL".equals(request.kind()) && request.operationId().matches("model:[1-9][0-9]{0,2}"));
        int value = Integer.parseInt(request.operationId().substring(6));
        require(value <= run.binding().maxModelCalls());
        return value;
    }
    private MapSqlParameterSource key(AuthorizedRun run) {
        return new MapSqlParameterSource().addValue("tenant", run.binding().tenantCode()).addValue("run", run.binding().runId());
    }
    private String json(Object value) { return new String(codec.write(value), StandardCharsets.UTF_8); }
    private <T> T read(String value, Class<T> type) { return codec.read(value.getBytes(StandardCharsets.UTF_8), type); }
    private <T> T readNullable(String value, Class<T> type) { return value == null ? null : read(value, type); }
    private static GraphGatewayException mismatch() { return new GraphGatewayException(BINDING_MISMATCH); }
    private record Call(int ordinal, String request, String configuration, long generation,
                        ModelProviderResponse response, Receipt receipt, String candidate) { }
}

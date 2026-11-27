package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.BINDING_MISMATCH;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.MODEL_BUDGET_EXHAUSTED;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.MODEL_IN_PROGRESS;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.REFERENCE_DENIED;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Decision;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Receipt;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.ToolIntent;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphMemory;
import io.github.illuseahashmap.agent.runtime.application.port.GraphCognitionPort;
import io.github.illuseahashmap.agent.runtime.application.port.GraphCompletionPort;
import io.github.illuseahashmap.agent.runtime.application.port.GraphDispatchPort.Observation;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** V2 receipt authority. Ordinal is storage order only, never a client-generated tool identity. */
final class JdbcGraphV2CognitionStore implements GraphCognitionPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcGraphExecutionStore executions;
    private final JdbcGraphReferenceStore references;
    private final GraphGatewayCodec codec;

    JdbcGraphV2CognitionStore(
            NamedParameterJdbcTemplate jdbc,
            JdbcGraphExecutionStore executions,
            GraphGatewayCodec codec) {
        this.jdbc = jdbc;
        this.executions = executions;
        this.codec = codec;
        this.references = new JdbcGraphReferenceStore(jdbc, executions, codec);
    }

    public History history(AuthorizedRun run, Invocation request) {
        return executions.withCurrentRun(run, () -> historyLocked(run, request));
    }

    public boolean hasResponse(AuthorizedRun run, Invocation request) {
        return executions.withCurrentRun(
                run,
                () -> {
                    historyLocked(run, request);
                    var call = find(run, request);
                    return call != null && call.response() != null;
                });
    }

    public ModelClaim beginModel(
            AuthorizedRun run, Invocation request, String configurationDigest) {
        return executions.withCurrentRun(
                run,
                () -> {
                    historyLocked(run, request);
                    require("MODEL".equals(request.kind()));
                    Call old = find(run, request);
                    if (old != null) {
                        if (old.response() != null) {
                            return new ModelClaim(null, old.response(), old.receipt());
                        }
                        if (!old.configuration().equals(configurationDigest)) {
                            throw mismatch();
                        }
                        if (old.generation() >= run.generation()) {
                            throw new GraphGatewayException(MODEL_IN_PROGRESS);
                        }
                    }
                    int physical =
                            jdbc.queryForObject(
                                    "SELECT COALESCE(sum(physical_calls),0) FROM"
                                        + " agent_graph_v2_model_call WHERE tenant_code=:tenant AND"
                                        + " run_id=:run",
                                    key(run),
                                    Integer.class);
                    if (physical >= run.binding().maxModelCalls()) {
                        throw new GraphGatewayException(MODEL_BUDGET_EXHAUSTED);
                    }
                    var params =
                            key(run).addValue("op", request.operationId())
                                    .addValue("request", codec.fingerprint(request))
                                    .addValue("requestJson", json(request))
                                    .addValue("configuration", configurationDigest)
                                    .addValue("generation", run.generation())
                                    .addValue("ticket", UUID.randomUUID())
                                    .addValue("candidate", "candidate:" + UUID.randomUUID());
                    if (old == null) {
                        params.addValue("ordinal", calls(run).size() + 1);
                        jdbc.update(
                                """
                                INSERT INTO agent_graph_v2_model_call(tenant_code,run_id,ordinal,operation_id,
                                request_json,request_digest,configuration_digest,generation,ticket,candidate_ref)
                                VALUES(:tenant,:run,:ordinal,:op,:requestJson,:request,:configuration,:generation,:ticket,:candidate)
                                """,
                                params);
                    } else {
                        jdbc.update(
                                """
                                UPDATE agent_graph_v2_model_call SET generation=:generation,ticket=:ticket,physical_calls=physical_calls+1
                                WHERE tenant_code=:tenant AND run_id=:run AND operation_id=:op AND response_json IS NULL
                                """,
                                params);
                    }
                    return new ModelClaim(params.getValue("ticket").toString(), null, null);
                });
    }

    public void saveResponse(
            AuthorizedRun run, Invocation request, String ticket, ModelProviderResponse response) {
        require(response != null);
        String body = json(response);
        read(body, ModelProviderResponse.class);
        executions.withCurrentRun(
                run,
                () -> {
                    int changed =
                            jdbc.update(
                                    """
                                    UPDATE agent_graph_v2_model_call SET response_json=:body
                                    WHERE tenant_code=:tenant AND run_id=:run AND operation_id=:op AND request_digest=:request
                                      AND generation=:generation AND ticket=CAST(:ticket AS uuid) AND response_json IS NULL
                                    """,
                                    key(run).addValue("op", request.operationId())
                                            .addValue("request", codec.fingerprint(request))
                                            .addValue("generation", run.generation())
                                            .addValue("ticket", ticket)
                                            .addValue("body", body));
                    if (changed != 1) {
                        throw mismatch();
                    }
                    return null;
                });
    }

    public Receipt finishModel(
            AuthorizedRun run, Invocation request, String tool, String argumentsJson) {
        return finishModels(
                run,
                request,
                tool == null
                        ? List.of()
                        : List.of(new ModelProviderResponse.ToolCall(tool, argumentsJson, null)));
    }

    public Receipt finishModels(
            AuthorizedRun run, Invocation request, List<ModelProviderResponse.ToolCall> tools) {
        require(
                tools != null
                        && tools.size() <= 16
                        && tools.stream().noneMatch(t -> GraphMemory.READ_TOOL.equals(t.name())));
        return executions.withCurrentRun(
                run,
                () -> {
                    var history = historyLocked(run, request);
                    Call call = find(run, request);
                    if (call == null || call.response() == null) {
                        throw mismatch();
                    }
                    if (call.receipt() != null) {
                        var saved = call.receipt().decision().tools();
                        require(saved.size() == tools.size());
                        for (int i = 0; i < saved.size(); i++) {
                            require(
                                    saved.get(i).toolCode().equals(tools.get(i).name())
                                            && saved.get(i)
                                                    .argumentsDigest()
                                                    .equals(
                                                            GraphGatewayCodec.sha256(
                                                                    tools.get(i).argumentsJson())));
                        }
                        new JdbcGraphCorrectionStore(jdbc, codec)
                                .complete(run, request, call.receipt());
                        return call.receipt();
                    }
                    int issued =
                            calls(run).stream()
                                    .filter(c -> c.receipt() != null)
                                    .mapToInt(
                                            c ->
                                                    (int)
                                                            c.receipt().decision().tools().stream()
                                                                    .filter(
                                                                            t ->
                                                                                    !GraphMemory
                                                                                            .READ_TOOL
                                                                                            .equals(
                                                                                                    t
                                                                                                            .toolCode()))
                                                                    .count())
                                    .sum();
                    if (issued + tools.size() > run.binding().maxToolCalls()) {
                        throw new GraphGatewayException(
                                GraphGatewayException.Code.TOOL_BUDGET_EXHAUSTED);
                    }
                    var intents = new ArrayList<ToolIntent>();
                    for (var tool : tools) {
                        // The immutable arguments reference IS the v2 tool-intent identity. No
                        // second ordinal is inferred.
                        String id = "intent:" + UUID.randomUUID();
                        String ref =
                                references.recordArgumentsInTransaction(
                                        run, id, tool.name(), tool.argumentsJson());
                        intents.add(
                                new ToolIntent(
                                        tool.name(),
                                        ref,
                                        GraphGatewayCodec.sha256(tool.argumentsJson())));
                    }
                    new JdbcGraphPlanStore(jdbc, executions, codec)
                            .bind(run, request, history, call.response().content(), intents);
                    var decision = new Decision(intents, tools.isEmpty() ? call.candidate() : null);
                    var receipt =
                            new Receipt(
                                    codec.fingerprint(run.binding()),
                                    request.operationId(),
                                    codec.fingerprint(request),
                                    "model-receipt:" + UUID.randomUUID(),
                                    decision,
                                    null);
                    jdbc.update(
                            "UPDATE agent_graph_v2_model_call SET receipt_json=:body WHERE"
                                    + " tenant_code=:tenant AND run_id=:run AND operation_id=:op",
                            key(run).addValue("op", request.operationId())
                                    .addValue("body", json(receipt)));
                    new JdbcGraphCorrectionStore(jdbc, codec).complete(run, request, receipt);
                    return receipt;
                });
    }

    public Receipt finishSummary(AuthorizedRun run, Invocation request) {
        return executions.withCurrentRun(
                run,
                () -> {
                    var history = historyLocked(run, request);
                    var call = find(run, request);
                    require(
                            request.operationId().startsWith("summary:")
                                    && history.memory() != null
                                    && call != null
                                    && call.response() != null
                                    && call.response().toolCall() == null);
                    if (call.receipt() != null) {
                        return call.receipt();
                    }
                    var receipt =
                            new Receipt(
                                    codec.fingerprint(run.binding()),
                                    request.operationId(),
                                    codec.fingerprint(request),
                                    "model-receipt:" + UUID.randomUUID(),
                                    new Decision(List.of(), "memory:" + UUID.randomUUID()),
                                    null);
                    new JdbcGraphMemoryStore(jdbc, codec)
                            .saveSummary(
                                    run,
                                    request,
                                    history.memory(),
                                    history.tools(),
                                    call.response().content(),
                                    receipt);
                    saveContextReceipt(run, request, receipt);
                    return receipt;
                });
    }

    public Receipt finishReadIntent(
            AuthorizedRun run, Invocation request, GraphMemory.ReadAction action) {
        return executions.withCurrentRun(
                run,
                () -> {
                    var history = historyLocked(run, request);
                    var call = find(run, request);
                    require(
                            history.memory() != null
                                    && call != null
                                    && call.response() != null
                                    && history.tools().stream()
                                            .anyMatch(
                                                    f ->
                                                            f.receiptRef()
                                                                    .equals(action.reference())));
                    if (call.receipt() != null) {
                        new JdbcGraphCorrectionStore(jdbc, codec)
                                .complete(run, request, call.receipt());
                        return call.receipt();
                    }
                    String args = json(action);
                    String id = "intent:" + UUID.randomUUID();
                    String ref =
                            references.recordArgumentsInTransaction(
                                    run, id, GraphMemory.READ_TOOL, args);
                    var intent =
                            new ToolIntent(
                                    GraphMemory.READ_TOOL, ref, GraphGatewayCodec.sha256(args));
                    new JdbcGraphMemoryStore(jdbc, codec)
                            .saveReadIntent(run, request, intent, action);
                    var receipt =
                            new Receipt(
                                    codec.fingerprint(run.binding()),
                                    request.operationId(),
                                    codec.fingerprint(request),
                                    "model-receipt:" + UUID.randomUUID(),
                                    new Decision(List.of(intent), null),
                                    null);
                    saveContextReceipt(run, request, receipt);
                    new JdbcGraphCorrectionStore(jdbc, codec).complete(run, request, receipt);
                    return receipt;
                });
    }

    public Receipt readResult(AuthorizedRun run, Invocation request) {
        return executions.withCurrentRun(
                run,
                () -> {
                    var history = historyLocked(run, request);
                    return new JdbcGraphMemoryStore(jdbc, codec)
                            .readResult(run, request, history.tools());
                });
    }

    private void saveContextReceipt(AuthorizedRun run, Invocation request, Receipt receipt) {
        jdbc.update(
                "UPDATE agent_graph_v2_model_call SET receipt_json=:body WHERE tenant_code=:tenant"
                        + " AND run_id=:run AND operation_id=:op",
                key(run).addValue("op", request.operationId()).addValue("body", json(receipt)));
    }

    public Receipt finishValidation(
            AuthorizedRun run, Invocation request, boolean accepted, String reasonCode) {
        require(
                "VALIDATE".equals(request.kind())
                        && reasonCode != null
                        && reasonCode.matches("[A-Z0-9_]{1,64}"));
        return executions.withCurrentRun(
                run,
                () -> {
                    var history = historyLocked(run, request);
                    var params = key(run).addValue("op", request.operationId());
                    var old =
                            jdbc.query(
                                    "SELECT request_digest,receipt_json FROM"
                                        + " agent_graph_v2_validation WHERE tenant_code=:tenant AND"
                                        + " run_id=:run AND operation_id=:op",
                                    params,
                                    (rs, n) -> {
                                        require(rs.getString(1).equals(codec.fingerprint(request)));
                                        return read(rs.getString(2), Receipt.class);
                                    });
                    if (!old.isEmpty()) {
                        require(
                                old.getFirst().accepted() == accepted
                                        || request.operationId().startsWith("plan-validate:"));
                        return old.getFirst();
                    }
                    boolean planning =
                            request.operationId().startsWith("plan-validate:")
                                    && history.plan() != null;
                    var plan =
                            planning && accepted
                                    ? read(
                                            history.candidate().content(),
                                            io.github.illuseahashmap.agent.runtime.application.graph
                                                    .GraphPlan.class)
                                    : null;
                    var plans = new JdbcGraphPlanStore(jdbc, executions, codec);
                    boolean revisionValid =
                            !planning
                                    || !accepted
                                    || plans.validRevision(run, request, history, plan);
                    if (planning && accepted && revisionValid && plan.revision() > 1) {
                        plans.requireSafeSwitch(run);
                    }
                    boolean decision =
                            accepted && revisionValid && (!planning || plans.affordable(run, plan));
                    String reason =
                            !revisionValid
                                    ? "GRAPH_PLAN_REVISION_REJECTED"
                                    : accepted && !decision
                                            ? "GRAPH_PLAN_BUDGET_INSUFFICIENT"
                                            : reasonCode;
                    var receipt =
                            new Receipt(
                                    codec.fingerprint(run.binding()),
                                    request.operationId(),
                                    codec.fingerprint(request),
                                    "validation:" + UUID.randomUUID(),
                                    null,
                                    decision);
                    jdbc.update(
                            """
                            INSERT INTO agent_graph_v2_validation(tenant_code,run_id,operation_id,request_json,request_digest,receipt_json,reason_code)
                            VALUES(:tenant,:run,:op,:requestJson,:digest,:body,:reason)
                            """,
                            params.addValue("requestJson", json(request))
                                    .addValue("digest", codec.fingerprint(request))
                                    .addValue("body", json(receipt))
                                    .addValue("reason", reason));
                    if (planning) {
                        plans.verdict(run, request, decision, plan);
                        new JdbcGraphCorrectionStore(jdbc, codec)
                                .completeReplan(run, request, receipt);
                    }
                    if (history.taskFacts() != null && !planning) {
                        jdbc.update(
                                "INSERT INTO agent_graph_fact_validation"
                                        + " VALUES(:tenant,:run,:op,:candidate,:report)",
                                params.addValue("candidate", request.inputRefs().getFirst())
                                        .addValue("report", json(history.taskFacts())));
                    }
                    if (!decision && !planning) {
                        new JdbcGraphFormatStore(jdbc, codec).offer(run, request, reason);
                    }
                    return receipt;
                });
    }

    void redirectFormat(AuthorizedRun run, Invocation request) {
        boolean redirect =
                executions.withCurrentRun(
                        run, () -> new JdbcGraphFormatStore(jdbc, codec).redirect(run, request));
        if (redirect) {
            throw new GraphGatewayException(GraphGatewayException.Code.FORMAT_REPAIR_REQUIRED);
        }
    }

    GraphCompletionPort.Evidence completionEvidence(AuthorizedRun run, Observation observation) {
        var all = calls(run);
        require(!all.isEmpty());
        var last = all.getLast();
        require(
                !last.invocation().operationId().startsWith("summary:")
                        && last.receipt() != null
                        && last.receipt().decision().candidateRef() != null
                        && last.candidate().equals(observation.candidateRef()));
        var evidence =
                jdbc
                        .query(
                                "SELECT request_json,receipt_json,reason_code FROM"
                                        + " agent_graph_v2_validation WHERE tenant_code=:tenant AND"
                                        + " run_id=:run",
                                key(run),
                                (rs, n) ->
                                        new Validation(
                                                read(rs.getString(1), Invocation.class),
                                                read(rs.getString(2), Receipt.class),
                                                rs.getString(3)))
                        .stream()
                        .filter(
                                v ->
                                        v.receipt()
                                                .receiptRef()
                                                .equals(observation.validationReceiptRef()))
                        .toList();
        require(evidence.size() == 1);
        var v = evidence.getFirst();
        require(
                v.request().inputRefs().getFirst().equals(observation.candidateRef())
                        && codec.fingerprint(v.request()).equals(v.receipt().requestDigest())
                        && codec.fingerprint(run.binding()).equals(v.receipt().bindingDigest())
                        && v.request().operationId().equals(v.receipt().operationId())
                        && (v.receipt().accepted() ? "CANDIDATE_READY" : "REJECTED")
                                .equals(observation.status()));
        require(!v.receipt().accepted() || !v.request().operationId().startsWith("plan-validate:"));
        return new GraphCompletionPort.Evidence(
                run, historyLocked(run, v.request()), v.receipt().accepted(), v.reason());
    }

    History historyLocked(AuthorizedRun run, Invocation request) {
        require(run.binding().stateSchemaVersion() == 2 && !request.inputRefs().isEmpty());
        boolean localRead = "READ_RESULT".equals(request.kind());
        boolean model = "MODEL".equals(request.kind()) || localRead;
        require(model || "VALIDATE".equals(request.kind()));
        var all = calls(run);
        Call current =
                localRead
                        ? all.stream()
                                .filter(
                                        c ->
                                                c.receipt() != null
                                                        && c.receipt()
                                                                .decision()
                                                                .tools()
                                                                .contains(request.tool()))
                                .findFirst()
                                .orElseThrow(JdbcGraphV2CognitionStore::mismatch)
                        : model
                                ? find(run, request)
                                : all.stream()
                                        .filter(
                                                c ->
                                                        c.candidate()
                                                                        .equals(
                                                                                request.inputRefs()
                                                                                        .getFirst())
                                                                && !c.invocation()
                                                                        .operationId()
                                                                        .startsWith("summary:")
                                                                && c.receipt() != null
                                                                && c.receipt()
                                                                                .decision()
                                                                                .candidateRef()
                                                                        != null)
                                        .findFirst()
                                        .orElseThrow(JdbcGraphV2CognitionStore::mismatch);
        if (current != null && model && !localRead) {
            // Replay uses its original committed causal inputs, not newer calls appended to the
            // Run.
            require(codec.fingerprint(current.invocation()).equals(codec.fingerprint(request)));
        }
        int until =
                current == null
                        ? Integer.MAX_VALUE
                        : current.ordinal() + (model && !localRead ? 0 : 1);
        var expected = new HashSet<String>();
        var factsByRef = new HashMap<String, ToolFact>();
        int superseded = new JdbcGraphCorrectionStore(jdbc, codec).supersededOrdinal(run);
        for (Call call : all) {
            if (call.ordinal() >= until) {
                continue;
            }
            if (call.receipt() == null) {
                throw mismatch();
            }
            expected.add(call.receipt().receiptRef());
            if (call.ordinal() == superseded) {
                continue;
            }
            for (var tool : call.receipt().decision().tools()) {
                if (GraphMemory.READ_TOOL.equals(tool.toolCode())) {
                    if (!localRead || !tool.equals(request.tool())) {
                        expected.add(new JdbcGraphMemoryStore(jdbc, codec).readReceipt(run, tool));
                    }
                    continue;
                }
                var fact = fact(run, tool);
                expected.add(fact.receiptRef());
                factsByRef.put(fact.receiptRef(), fact);
            }
        }
        require(
                request.inputRefs()
                        .getFirst()
                        .equals(model ? run.binding().inputRef() : current.candidate()));
        List<String> submitted =
                request.inputRefs().subList(1, request.inputRefs().size()).stream()
                        .filter(r -> !GraphMemory.contextReference(r))
                        .toList();
        require(new HashSet<>(request.inputRefs()).size() == request.inputRefs().size());
        // Initial v2 keeps complete evidence: order may vary, omission/foreign/duplicate references
        // are denied.
        if (submitted.size() != expected.size() || !expected.equals(new HashSet<>(submitted))) {
            throw new GraphGatewayException(REFERENCE_DENIED);
        }
        var facts =
                submitted.stream().filter(factsByRef::containsKey).map(factsByRef::get).toList();
        String input =
                jdbc.queryForObject(
                        "SELECT input_json FROM agent_graph_execution WHERE tenant_code=:tenant AND"
                                + " run_id=:run",
                        key(run),
                        String.class);
        return new History(
                input,
                facts,
                model ? null : current.response(),
                new JdbcGraphTaskStore(jdbc, codec).report(run, input, facts),
                new JdbcGraphPlanStore(jdbc, executions, codec).context(run),
                new JdbcGraphMemoryStore(jdbc, codec).context(run, request, facts),
                new JdbcGraphFormatStore(jdbc, codec).context(run, request));
    }

    ToolFact fact(AuthorizedRun run, ToolIntent tool) {
        var p =
                key(run).addValue("op", tool.argumentsRef())
                        .addValue("ref", tool.argumentsRef())
                        .addValue("tool", tool.toolCode())
                        .addValue("digest", tool.argumentsDigest())
                        .addValue("binding", codec.fingerprint(run.binding()));
        var args =
                jdbc.query(
                        """
                        SELECT body FROM agent_graph_reference WHERE tenant_code=:tenant AND run_id=:run AND kind='ARGUMENTS'
                          AND operation_id=:op AND reference=:ref AND tool_code=:tool AND content_digest=:digest AND binding_digest=:binding
                        """,
                        p,
                        (rs, n) -> rs.getString(1));
        var receipts =
                jdbc.query(
                        """
                        SELECT reference,body FROM agent_graph_reference WHERE tenant_code=:tenant AND run_id=:run AND kind='TOOL_RECEIPT'
                          AND operation_id=:op AND tool_code=:tool AND binding_digest=:binding
                        """,
                        p,
                        (rs, n) ->
                                Map.entry(rs.getString(1), read(rs.getString(2), JsonNode.class)));
        require(args.size() == 1 && receipts.size() == 1);
        var body = receipts.getFirst().getValue();
        var outputs =
                jdbc.query(
                        """
                        SELECT output_snapshot FROM agent_tool_execution_audit WHERE tenant_code=:tenant AND tool_code=:tool AND status='SUCCEEDED'
                          AND id=:audit AND idempotency_key=:key
                        """,
                        p.addValue("audit", body.path("audit_id").asLong())
                                .addValue(
                                        "key",
                                        run.binding().runId()
                                                + ":"
                                                + tool.argumentsRef()
                                                + ":"
                                                + tool.toolCode()),
                        (rs, n) -> rs.getString(1));
        require(
                outputs.size() == 1
                        && outputs.getFirst() != null
                        && GraphGatewayCodec.sha256(outputs.getFirst())
                                .equals(body.path("output_digest").asText()));
        return new ToolFact(
                receipts.getFirst().getKey(),
                tool.toolCode(),
                args.getFirst(),
                outputs.getFirst(),
                body.path("output_digest").asText());
    }

    private Call find(AuthorizedRun run, Invocation request) {
        var old =
                calls(run).stream()
                        .filter(c -> c.invocation().operationId().equals(request.operationId()))
                        .findFirst()
                        .orElse(null);
        if (old != null
                && !codec.fingerprint(old.invocation()).equals(codec.fingerprint(request))) {
            throw mismatch();
        }
        return old;
    }

    private List<Call> calls(AuthorizedRun run) {
        return jdbc.query(
                "SELECT * FROM agent_graph_v2_model_call WHERE tenant_code=:tenant AND run_id=:run"
                        + " ORDER BY ordinal",
                key(run),
                (rs, n) ->
                        new Call(
                                rs.getInt("ordinal"),
                                read(rs.getString("request_json"), Invocation.class),
                                rs.getString("configuration_digest"),
                                rs.getLong("generation"),
                                readNullable(
                                        rs.getString("response_json"), ModelProviderResponse.class),
                                readNullable(rs.getString("receipt_json"), Receipt.class),
                                rs.getString("candidate_ref")));
    }

    private MapSqlParameterSource key(AuthorizedRun run) {
        return new MapSqlParameterSource()
                .addValue("tenant", run.binding().tenantCode())
                .addValue("run", run.binding().runId());
    }

    private String json(Object value) {
        return new String(codec.write(value), StandardCharsets.UTF_8);
    }

    private <T> T read(String value, Class<T> type) {
        return codec.read(value.getBytes(StandardCharsets.UTF_8), type);
    }

    private <T> T readNullable(String value, Class<T> type) {
        return value == null ? null : read(value, type);
    }

    private static GraphGatewayException mismatch() {
        return new GraphGatewayException(BINDING_MISMATCH);
    }

    private record Call(
            int ordinal,
            Invocation invocation,
            String configuration,
            long generation,
            ModelProviderResponse response,
            Receipt receipt,
            String candidate) {}

    private record Validation(Invocation request, Receipt receipt, String reason) {}
}

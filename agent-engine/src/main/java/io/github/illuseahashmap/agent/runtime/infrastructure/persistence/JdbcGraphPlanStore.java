package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Receipt;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.ToolIntent;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphPlan;
import io.github.illuseahashmap.agent.runtime.application.port.GraphCognitionPort.History;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Immutable plan policy, verdict and intent association. Caller holds the Run lock; no scheduler.
 */
final class JdbcGraphPlanStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcGraphExecutionStore executions;
    private final GraphGatewayCodec codec;

    JdbcGraphPlanStore(
            NamedParameterJdbcTemplate jdbc,
            JdbcGraphExecutionStore executions,
            GraphGatewayCodec codec) {
        this.jdbc = jdbc;
        this.executions = executions;
        this.codec = codec;
    }

    private MapSqlParameterSource key(AuthorizedRun run) {
        return new MapSqlParameterSource("tenant", run.binding().tenantCode())
                .addValue("run", run.binding().runId());
    }

    GraphPlan.Context context(AuthorizedRun run) {
        var rows =
                jdbc.query(
                        "SELECT policy_json FROM agent_graph_plan_policy WHERE tenant_code=:tenant"
                                + " AND run_id=:run",
                        key(run),
                        (rs, n) -> read(rs.getString(1), GraphPlan.Policy.class));
        if (rows.isEmpty()) {
            return null;
        }
        var accepted =
                jdbc.query(
                        "SELECT plan_json FROM agent_graph_plan_verdict WHERE tenant_code=:tenant"
                                + " AND run_id=:run AND accepted ORDER BY"
                                + " (plan_json::jsonb->>'revision')::int DESC",
                        key(run),
                        (rs, n) -> read(rs.getString(1), GraphPlan.class));
        return new GraphPlan.Context(
                rows.getFirst(), accepted.isEmpty() ? null : accepted.getFirst());
    }

    String guard(AuthorizedRun run, Invocation request) {
        if (request.operationId().startsWith("summary:") || "READ_RESULT".equals(request.kind())) {
            require(new JdbcGraphMemoryStore(jdbc, codec).policy(run) != null);
            return null;
        }
        var context = context(run);
        boolean plan = request.operationId().startsWith("plan:");
        boolean check = request.operationId().startsWith("plan-validate:");
        if (context == null) {
            if (plan || check) {
                throw denied();
            }
            return null;
        }
        if (plan) {
            if (!"MODEL".equals(request.kind())
                    || !(request.operationId().equals("plan:" + codec.fingerprint(run.binding()))
                            || (context.policy().version() == 2
                                    && new JdbcGraphCorrectionStore(jdbc, codec)
                                            .ownsReplan(run, request.operationId())))) {
                throw denied();
            }
            return null;
        }
        if (check) {
            require("VALIDATE".equals(request.kind()) && !request.inputRefs().isEmpty());
            var candidate = request.inputRefs().getFirst();
            require(
                    request.operationId()
                            .equals(
                                    "plan-validate:"
                                            + codec.fingerprint(
                                                    List.of(
                                                            codec.fingerprint(run.binding()),
                                                            candidate))));
            var operations =
                    jdbc.query(
                            "SELECT operation_id FROM agent_graph_v2_model_call WHERE"
                                    + " tenant_code=:tenant AND run_id=:run AND"
                                    + " candidate_ref=:candidate",
                            key(run).addValue("candidate", candidate),
                            (rs, n) -> rs.getString(1));
            require(
                    operations.size() == 1
                            && (operations
                                            .getFirst()
                                            .equals("plan:" + codec.fingerprint(run.binding()))
                                    || new JdbcGraphCorrectionStore(jdbc, codec)
                                            .ownsReplan(run, operations.getFirst())));
            return null;
        }
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM agent_graph_plan_verdict WHERE tenant_code=:tenant"
                                + " AND run_id=:run AND NOT accepted",
                        key(run),
                        Integer.class)
                > 0) {
            throw denied();
        }
        if (context.accepted() == null) {
            return "PLAN";
        }
        if ("TOOL".equals(request.kind())) {
            var steps =
                    jdbc.query(
                            "SELECT i.step_id FROM agent_graph_plan_intent i JOIN"
                                + " agent_graph_plan_verdict v ON v.tenant_code=i.tenant_code AND"
                                + " v.run_id=i.run_id AND v.operation_id=i.plan_operation_id WHERE"
                                + " i.tenant_code=:tenant AND i.run_id=:run AND"
                                + " i.intent_ref=:intent AND"
                                + " (v.plan_json::jsonb->>'revision')::int=:revision",
                            key(run).addValue("intent", request.operationId())
                                    .addValue("revision", context.accepted().revision()),
                            (rs, n) -> rs.getString(1));
            if (steps.size() != 1) {
                throw denied();
            }
            var history = dependencies(run, request);
            context.accepted()
                    .executable(steps.getFirst(), history.taskFacts(), request.tool().toolCode());
            var args =
                    new JdbcGraphReferenceStore(jdbc, executions, codec)
                            .readArguments(run, request);
            GraphPlan.requireSubject(
                    read(args.json(), JsonNode.class),
                    new JdbcGraphTaskStore(jdbc, codec).contract(run).subjectField(),
                    history.taskFacts().subject());
        }
        return null;
    }

    History dependencies(AuthorizedRun run, Invocation request) {
        var parents =
                jdbc
                        .query(
                                "SELECT request_json,receipt_json FROM agent_graph_v2_model_call"
                                    + " WHERE tenant_code=:tenant AND run_id=:run AND receipt_json"
                                    + " IS NOT NULL",
                                key(run),
                                (rs, n) ->
                                        Map.entry(
                                                read(rs.getString(1), Invocation.class),
                                                read(rs.getString(2), Receipt.class)))
                        .stream()
                        .filter(e -> e.getValue().decision().tools().contains(request.tool()))
                        .toList();
        require(parents.size() == 1);
        return new JdbcGraphV2CognitionStore(jdbc, executions, codec)
                .historyLocked(run, parents.getFirst().getKey());
    }

    void bind(
            AuthorizedRun run,
            Invocation request,
            History history,
            String response,
            List<ToolIntent> intents) {
        var context = context(run);
        if (context == null || intents.isEmpty()) {
            return;
        }
        require(
                context.accepted() != null
                        && intents.size() == 1
                        && !request.operationId().startsWith("plan:"));
        var body = read(response, JsonNode.class);
        context.accepted().validateActionIdentity(body);
        var intent = intents.getFirst();
        var step =
                context.accepted()
                        .executable(
                                body.path("plan_step_id").asText(),
                                history.taskFacts(),
                                intent.toolCode());
        var args =
                new JdbcGraphReferenceStore(jdbc, executions, codec)
                        .readArguments(
                                run,
                                new Invocation("TOOL", intent.argumentsRef(), List.of(), intent));
        GraphPlan.requireSubject(
                read(args.json(), JsonNode.class),
                new JdbcGraphTaskStore(jdbc, codec).contract(run).subjectField(),
                history.taskFacts().subject());
        jdbc.update(
                """
                INSERT INTO agent_graph_plan_intent(tenant_code,run_id,intent_ref,plan_operation_id,step_id)
                SELECT :tenant,:run,:intent,operation_id,:step
                FROM agent_graph_plan_verdict
                WHERE tenant_code=:tenant
                AND run_id=:run
                AND accepted
                AND (plan_json::jsonb->>'revision')::int=:revision
                """,
                key(run).addValue("intent", intent.argumentsRef())
                        .addValue("step", step.stepId())
                        .addValue("revision", context.accepted().revision()));
    }

    boolean affordable(AuthorizedRun run, GraphPlan plan) {
        int steps = plan.steps().stream().mapToInt(s -> s.capabilityChoices().size()).sum();
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        SELECT (SELECT count(*) FROM agent_graph_budget_operation WHERE tenant_code=:tenant AND run_id=:run AND kind='MODEL')+:steps+1<=:models
                          AND (SELECT COALESCE(sum(physical_calls),0) FROM agent_graph_v2_model_call WHERE tenant_code=:tenant AND run_id=:run)+:steps+1<=:models
                          AND (SELECT count(*)
                          FROM agent_graph_budget_call c
                          JOIN agent_graph_budget_operation o USING(tenant_code,run_id,operation_id)
                          WHERE c.tenant_code=:tenant
                          AND c.run_id=:run
                          AND o.kind='TOOL')+:steps<=:tools
                          AND (SELECT count(*) FROM agent_graph_budget_operation WHERE tenant_code=:tenant AND run_id=:run AND kind='TOOL')+:steps<=:tools
                          AND (SELECT count(*) FROM agent_graph_budget_operation WHERE tenant_code=:tenant AND run_id=:run AND kind<>'VALIDATE')+2*:steps+1<=logical_limit
                          AND (SELECT count(*) FROM agent_graph_budget_call WHERE tenant_code=:tenant AND run_id=:run)+2*:steps+1<=physical_limit
                          AND (SELECT count(*)
                          FROM agent_graph_budget_operation
                          WHERE tenant_code=:tenant
                          AND run_id=:run
                          AND kind IN ('VALIDATE','READ_RESULT'))+1<=validation_limit
                        FROM agent_graph_run_budget WHERE tenant_code=:tenant AND run_id=:run
                        """,
                        key(run).addValue("steps", steps)
                                .addValue("models", run.binding().maxModelCalls())
                                .addValue("tools", run.binding().maxToolCalls()),
                        Boolean.class));
    }

    boolean validRevision(AuthorizedRun run, Invocation request, History history, GraphPlan plan) {
        var model =
                jdbc.queryForObject(
                        "SELECT operation_id FROM agent_graph_v2_model_call WHERE"
                            + " tenant_code=:tenant AND run_id=:run AND candidate_ref=:candidate",
                        key(run).addValue("candidate", request.inputRefs().getFirst()),
                        String.class);
        if (!model.startsWith("plan:correction:")) {
            return plan.revision() == 1 && plan.parentRevision() == 0;
        }
        if (history.plan().policy().version() != 2
                || !new JdbcGraphCorrectionStore(jdbc, codec).ownsReplan(run, model)) {
            return false;
        }
        try {
            plan.validateRevision(
                    history.plan().accepted(),
                    history.tools().stream()
                            .map(t -> t.receiptRef())
                            .collect(java.util.stream.Collectors.toSet()));
            return true;
        } catch (GraphGatewayException invalid) {
            return false;
        }
    }

    void requireSafeSwitch(AuthorizedRun run) {
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM agent_graph_call_permit WHERE tenant_code=:tenant AND"
                                + " run_id=:run AND state<>'RELEASED'",
                        key(run),
                        Integer.class)
                != 0) {
            throw new GraphGatewayException(GraphGatewayException.Code.PLAN_SWITCH_PENDING);
        }
    }

    void verdict(AuthorizedRun run, Invocation request, boolean accepted, GraphPlan plan) {
        jdbc.update(
                "INSERT INTO agent_graph_plan_verdict"
                        + " VALUES(:tenant,:run,:op,:candidate,:accepted,:body)",
                key(run).addValue("op", request.operationId())
                        .addValue("candidate", request.inputRefs().getFirst())
                        .addValue("accepted", accepted)
                        .addValue(
                                "body",
                                accepted
                                        ? new String(codec.write(plan), StandardCharsets.UTF_8)
                                        : null));
    }

    private <T> T read(String body, Class<T> type) {
        return codec.read(body == null ? null : body.getBytes(StandardCharsets.UTF_8), type);
    }

    private static GraphGatewayException denied() {
        return new GraphGatewayException(GraphGatewayException.Code.PLAN_DENIED);
    }
}

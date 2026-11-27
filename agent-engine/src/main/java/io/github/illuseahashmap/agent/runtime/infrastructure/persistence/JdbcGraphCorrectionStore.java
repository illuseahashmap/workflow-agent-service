package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Receipt;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * One immutable permission per Run, never a second scheduler. All methods run under the caller's
 * Run lock.
 */
final class JdbcGraphCorrectionStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final GraphGatewayCodec codec;

    JdbcGraphCorrectionStore(NamedParameterJdbcTemplate jdbc, GraphGatewayCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    private MapSqlParameterSource key(AuthorizedRun run) {
        return new MapSqlParameterSource("tenant", run.binding().tenantCode())
                .addValue("run", run.binding().runId());
    }

    private Grant grant(AuthorizedRun run) {
        var rows =
                jdbc.query(
                        """
                        SELECT c.*, EXISTS(SELECT 1 FROM agent_graph_correction_result r WHERE r.tenant_code=c.tenant_code AND r.run_id=c.run_id) AS finished
                        FROM agent_graph_correction c WHERE tenant_code=:tenant AND run_id=:run
                        """,
                        key(run),
                        (rs, n) ->
                                new Grant(
                                        rs.getString("operation_id"),
                                        rs.getString("trigger_operation_id"),
                                        rs.getString("trigger_digest"),
                                        rs.getInt("source_ordinal"),
                                        rs.getBoolean("finished")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    String guard(AuthorizedRun run, Invocation request) {
        var grant = grant(run);
        if (grant != null && request.operationId().equals(grant.trigger())) {
            require(codec.fingerprint(request).equals(grant.digest()));
            return redirect(
                    run); // Lost trigger ACK always redirects to the SAME correction, even after
            // its receipt was committed.
        }
        if ((request.operationId().startsWith("correction:")
                || request.operationId().startsWith("plan:correction:")
                || request.operationId().startsWith("repair:")
                || request.operationId().startsWith("route:repair:"))) {
            if (grant == null
                    || !grant.operation().equals(request.operationId())
                    || !"MODEL".equals(request.kind())) {
                throw denied();
            }
        } else if (grant != null
                && !grant.finished()
                && !isReplanValidation(run, request, grant)
                && !request.operationId().startsWith("summary:")
                && !"READ_RESULT".equals(request.kind())) {
            throw new GraphGatewayException(GraphGatewayException.Code.CORRECTION_PENDING);
        }
        if (grant != null && "TOOL".equals(request.kind())) {
            var source = source(run, grant.ordinal());
            if (source.decision().tools().stream()
                    .anyMatch(t -> t.argumentsRef().equals(request.operationId()))) {
                throw denied();
            }
        }
        var contract = new JdbcGraphTaskStore(jdbc, codec).contract(run);
        if (contract != null && "TOOL".equals(request.kind())) {
            require(
                    contract.requiredFacts().stream()
                            .anyMatch(f -> f.tool().equals(request.tool().toolCode())));
        }
        return null;
    }

    boolean hasBudget(AuthorizedRun run) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        SELECT (SELECT count(*) FROM agent_graph_budget_operation WHERE tenant_code=:tenant AND run_id=:run AND kind<>'VALIDATE') < b.logical_limit
                          AND (SELECT count(*) FROM agent_graph_budget_call WHERE tenant_code=:tenant AND run_id=:run) < b.physical_limit
                          AND (SELECT count(*) FROM agent_graph_budget_operation WHERE tenant_code=:tenant AND run_id=:run AND kind='MODEL') < :models
                          AND (SELECT COALESCE(sum(physical_calls),0) FROM agent_graph_v2_model_call WHERE tenant_code=:tenant AND run_id=:run) < :models
                          AND (SELECT count(*) FROM agent_graph_budget_operation WHERE tenant_code=:tenant AND run_id=:run
                            AND kind IN ('VALIDATE','READ_RESULT')) < b.validation_limit
                        FROM agent_graph_run_budget b WHERE tenant_code=:tenant AND run_id=:run
                        """,
                        key(run).addValue("models", run.binding().maxModelCalls()),
                        Boolean.class));
    }

    boolean offer(AuthorizedRun run, Invocation trigger) {
        var existing = grant(run);
        if (existing != null) {
            return existing.trigger().equals(trigger.operationId())
                    && existing.digest().equals(codec.fingerprint(trigger));
        }
        var contract = new JdbcGraphTaskStore(jdbc, codec).contract(run);
        if (contract == null || !contract.allowCorrection()) {
            return false;
        }
        var k = key(run);
        var ordinals =
                jdbc
                        .query(
                                "SELECT ordinal,receipt_json FROM agent_graph_v2_model_call WHERE"
                                    + " tenant_code=:tenant AND run_id=:run AND receipt_json IS NOT"
                                    + " NULL",
                                k,
                                (rs, n) -> java.util.Map.entry(rs.getInt(1), read(rs.getString(2))))
                        .stream()
                        .filter(e -> e.getValue().decision().tools().contains(trigger.tool()))
                        .toList();
        require(ordinals.size() == 1);
        int ordinal = ordinals.getFirst().getKey();
        // Only a safe boundary: no sibling from the abandoned proposal may have been sent.
        for (var tool : ordinals.getFirst().getValue().decision().tools()) {
            if (jdbc.queryForObject(
                            "SELECT count(*) FROM agent_graph_budget_call WHERE tenant_code=:tenant"
                                    + " AND run_id=:run AND operation_id=:op",
                            k.addValue("op", tool.argumentsRef()),
                            Long.class)
                    != 0) {
                return false;
            }
        }
        boolean room =
                Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                """
                                SELECT (SELECT count(*) FROM agent_graph_budget_operation WHERE tenant_code=:tenant AND run_id=:run AND kind<>'VALIDATE') < b.logical_limit
                                  AND (SELECT count(*) FROM agent_graph_budget_call WHERE tenant_code=:tenant AND run_id=:run) < b.physical_limit
                                  AND (SELECT count(*) FROM agent_graph_budget_operation WHERE tenant_code=:tenant AND run_id=:run AND kind='MODEL') < :models
                                  AND (SELECT COALESCE(sum(physical_calls),0) FROM agent_graph_v2_model_call WHERE tenant_code=:tenant AND run_id=:run) < :models
                                FROM agent_graph_run_budget b WHERE tenant_code=:tenant AND run_id=:run
                                """,
                                k.addValue("models", run.binding().maxModelCalls()),
                                Boolean.class));
        if (!room) {
            return false;
        }
        String digest = codec.fingerprint(trigger);
        String operation =
                (replanning(run) ? "plan:correction:" : "correction:")
                        + codec.fingerprint(List.of(codec.fingerprint(run.binding()), digest));
        jdbc.update(
                """
                INSERT INTO agent_graph_correction(tenant_code,run_id,operation_id,trigger_operation_id,trigger_digest,source_ordinal,reason)
                VALUES(:tenant,:run,:correction,:trigger,:digest,:ordinal,'LOOP_DETECTED')
                """,
                k.addValue("correction", operation)
                        .addValue("trigger", trigger.operationId())
                        .addValue("digest", digest)
                        .addValue("ordinal", ordinal));
        return true;
    }

    String redirect(AuthorizedRun run) {
        var operation = grant(run).operation();
        if (operation.startsWith("route:repair:")) {
            return "ROUTE_REPAIR";
        }
        return operation.startsWith("repair:")
                ? "REPAIR"
                : operation.startsWith("plan:") ? "REPLAN" : "CORRECT";
    }

    boolean replanning(AuthorizedRun run) {
        var rows =
                jdbc.query(
                        "SELECT policy_json FROM agent_graph_plan_policy WHERE tenant_code=:tenant"
                                + " AND run_id=:run",
                        key(run),
                        (rs, n) ->
                                codec.read(
                                        rs.getString(1).getBytes(StandardCharsets.UTF_8),
                                        io.github.illuseahashmap.agent.runtime.application.graph
                                                .GraphPlan.Policy.class));
        return !rows.isEmpty() && rows.getFirst().version() == 2;
    }

    boolean ownsReplan(AuthorizedRun run, String operation) {
        var grant = grant(run);
        return grant != null
                && grant.operation().startsWith("plan:correction:")
                && grant.operation().equals(operation);
    }

    private boolean isReplanValidation(AuthorizedRun run, Invocation request, Grant grant) {
        return grant.operation().startsWith("plan:")
                && "VALIDATE".equals(request.kind())
                && request.operationId().startsWith("plan-validate:")
                && !request.inputRefs().isEmpty()
                && jdbc.queryForObject(
                                "SELECT count(*) FROM agent_graph_v2_model_call WHERE"
                                    + " tenant_code=:tenant AND run_id=:run AND operation_id=:op"
                                    + " AND candidate_ref=:candidate",
                                key(run).addValue("op", grant.operation())
                                        .addValue("candidate", request.inputRefs().getFirst()),
                                Integer.class)
                        == 1;
    }

    void completeReplan(AuthorizedRun run, Invocation request, Receipt receipt) {
        var grant = grant(run);
        if (grant == null || !isReplanValidation(run, request, grant)) {
            return;
        }
        jdbc.update(
                "INSERT INTO agent_graph_correction_result VALUES(:tenant,:run,:op,:receipt) ON"
                        + " CONFLICT DO NOTHING",
                key(run).addValue("op", grant.operation())
                        .addValue("receipt", receipt.receiptRef()));
    }

    int supersededOrdinal(AuthorizedRun run) {
        var grant = grant(run);
        return grant == null ? -1 : grant.ordinal();
    }

    void complete(AuthorizedRun run, Invocation request, Receipt receipt) {
        var grant = grant(run);
        if (grant == null
                || !grant.operation().equals(request.operationId())
                || grant.operation().startsWith("plan:")) {
            return;
        }
        jdbc.update(
                """
                INSERT INTO agent_graph_correction_result VALUES(:tenant,:run,:op,:receipt) ON CONFLICT DO NOTHING
                """,
                key(run).addValue("op", request.operationId())
                        .addValue("receipt", receipt.receiptRef()));
        require(
                receipt.receiptRef()
                        .equals(
                                jdbc.queryForObject(
                                        "SELECT receipt_ref FROM agent_graph_correction_result"
                                                + " WHERE tenant_code=:tenant AND run_id=:run",
                                        key(run),
                                        String.class)));
    }

    private Receipt source(AuthorizedRun run, int ordinal) {
        return jdbc.queryForObject(
                "SELECT receipt_json FROM agent_graph_v2_model_call WHERE tenant_code=:tenant AND"
                        + " run_id=:run AND ordinal=:ordinal",
                key(run).addValue("ordinal", ordinal),
                (rs, n) -> read(rs.getString(1)));
    }

    private Receipt read(String text) {
        return codec.read(text.getBytes(StandardCharsets.UTF_8), Receipt.class);
    }

    private static GraphGatewayException denied() {
        return new GraphGatewayException(GraphGatewayException.Code.CORRECTION_DENIED);
    }

    private record Grant(
            String operation, String trigger, String digest, int ordinal, boolean finished) {}
}

package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphRoute;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;
import io.github.illuseahashmap.agent.runtime.application.routing.AgentRouteDecision;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Immutable route verdict and tool restriction, always under the caller's Run lock. */
final class JdbcGraphRouteStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final GraphGatewayCodec codec;

    JdbcGraphRouteStore(NamedParameterJdbcTemplate jdbc, GraphGatewayCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    private MapSqlParameterSource key(AuthorizedRun run) {
        return new MapSqlParameterSource("tenant", run.binding().tenantCode())
                .addValue("run", run.binding().runId());
    }

    private <T> T read(String body, Class<T> type) {
        return codec.read(body.getBytes(StandardCharsets.UTF_8), type);
    }

    GraphRoute.Context context(AuthorizedRun run) {
        var policies =
                jdbc.query(
                        "SELECT policy_json FROM agent_graph_route_policy WHERE tenant_code=:tenant"
                                + " AND run_id=:run",
                        key(run),
                        (rs, n) -> read(rs.getString(1), GraphRoute.Policy.class));
        if (policies.isEmpty()) {
            return null;
        }
        var accepted =
                jdbc.query(
                        "SELECT route_json FROM agent_graph_route_verdict WHERE tenant_code=:tenant"
                                + " AND run_id=:run AND accepted",
                        key(run),
                        (rs, n) -> read(rs.getString(1), AgentRouteDecision.class));
        var originals =
                jdbc.query(
                        """
                        SELECT m.response_json FROM agent_graph_correction c JOIN agent_graph_v2_model_call m
                          ON m.tenant_code=c.tenant_code AND m.run_id=c.run_id AND m.ordinal=c.source_ordinal
                        WHERE c.tenant_code=:tenant AND c.run_id=:run AND c.reason='ROUTE_INVALID'
                        """,
                        key(run),
                        (rs, n) -> read(rs.getString(1), ModelProviderResponse.class).content());
        return new GraphRoute.Context(
                policies.getFirst(),
                accepted.isEmpty() ? null : accepted.getFirst(),
                originals.isEmpty() ? null : originals.getFirst());
    }

    boolean ownsRepair(AuthorizedRun run, String operation) {
        return jdbc.queryForObject(
                        """
                        SELECT count(*) FROM agent_graph_correction WHERE tenant_code=:tenant AND run_id=:run
                          AND operation_id=:op AND reason='ROUTE_INVALID'
                        """,
                        key(run).addValue("op", operation),
                        Integer.class)
                == 1;
    }

    String guard(AuthorizedRun run, Invocation request) {
        var context = context(run);
        boolean routing = request.operationId().startsWith("route:");
        boolean validation = request.operationId().startsWith("route-validate:");
        if (context == null) {
            if (routing || validation) {
                throw denied();
            }
            return null;
        }
        if (routing) {
            require("MODEL".equals(request.kind()));
            if (!request.operationId().equals("route:" + codec.fingerprint(run.binding()))
                    && !ownsRepair(run, request.operationId())) {
                throw denied();
            }
            return null;
        }
        if (validation) {
            require("VALIDATE".equals(request.kind()) && !request.inputRefs().isEmpty());
            var candidate = request.inputRefs().getFirst();
            require(
                    request.operationId()
                            .equals(
                                    "route-validate:"
                                            + codec.fingerprint(
                                                    List.of(
                                                            codec.fingerprint(run.binding()),
                                                            candidate))));
            var ops =
                    jdbc.query(
                            "SELECT operation_id FROM agent_graph_v2_model_call WHERE"
                                    + " tenant_code=:tenant AND run_id=:run AND"
                                    + " candidate_ref=:candidate",
                            key(run).addValue("candidate", candidate),
                            (rs, n) -> rs.getString(1));
            require(
                    ops.size() == 1
                            && (ops.getFirst().equals("route:" + codec.fingerprint(run.binding()))
                                    || ownsRepair(run, ops.getFirst())));
            return null;
        }
        if (context.accepted() == null) {
            int verdicts =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM agent_graph_route_verdict WHERE"
                                    + " tenant_code=:tenant AND run_id=:run",
                            key(run),
                            Integer.class);
            if (verdicts > 0 || !"MODEL".equals(request.kind())) {
                throw denied();
            }
            return "ROUTE";
        }
        if ("TOOL".equals(request.kind())
                && !context.accepted().requestedTools().contains(request.tool().toolCode())) {
            throw denied();
        }
        if ("VALIDATE".equals(request.kind())) {
            require(
                    jdbc.queryForObject(
                                    """
                                    SELECT count(*) FROM agent_graph_v2_model_call WHERE tenant_code=:tenant AND run_id=:run
                                      AND candidate_ref=:candidate AND operation_id LIKE 'route:%'
                                    """,
                                    key(run).addValue("candidate", request.inputRefs().getFirst()),
                                    Integer.class)
                            == 0);
        }
        return null;
    }

    void verdict(
            AuthorizedRun run, Invocation request, boolean accepted, AgentRouteDecision route) {
        jdbc.update(
                "INSERT INTO agent_graph_route_verdict"
                        + " VALUES(:tenant,:run,:op,:candidate,:accepted,:body)",
                key(run).addValue("op", request.operationId())
                        .addValue("candidate", request.inputRefs().getFirst())
                        .addValue("accepted", accepted)
                        .addValue(
                                "body",
                                route == null
                                        ? null
                                        : new String(codec.write(route), StandardCharsets.UTF_8)));
    }

    void offerRepair(AuthorizedRun run, Invocation request, String reason) {
        if (!"AGENT_ROUTE_INVALID".equals(reason) || !context(run).policy().allowCorrection()) {
            return;
        }
        if (jdbc.queryForObject(
                                "SELECT count(*) FROM agent_graph_correction WHERE"
                                        + " tenant_code=:tenant AND run_id=:run",
                                key(run),
                                Integer.class)
                        != 0
                || !new JdbcGraphCorrectionStore(jdbc, codec).hasBudget(run)) {
            return;
        }
        var params = key(run).addValue("candidate", request.inputRefs().getFirst());
        int ordinal =
                jdbc.queryForObject(
                        "SELECT ordinal FROM agent_graph_v2_model_call WHERE tenant_code=:tenant"
                                + " AND run_id=:run AND candidate_ref=:candidate",
                        params,
                        Integer.class);
        String digest = codec.fingerprint(request);
        jdbc.update(
                """
                INSERT INTO agent_graph_correction(tenant_code,run_id,operation_id,trigger_operation_id,trigger_digest,source_ordinal,reason)
                VALUES(:tenant,:run,:op,:trigger,:digest,:ordinal,'ROUTE_INVALID')
                """,
                params.addValue(
                                "op",
                                "route:repair:"
                                        + codec.fingerprint(
                                                List.of(codec.fingerprint(run.binding()), digest)))
                        .addValue("trigger", request.operationId())
                        .addValue("digest", digest)
                        .addValue("ordinal", ordinal));
    }

    boolean redirect(AuthorizedRun run, Invocation request) {
        return jdbc.queryForObject(
                        """
                        SELECT count(*) FROM agent_graph_correction WHERE tenant_code=:tenant AND run_id=:run
                          AND trigger_operation_id=:op AND trigger_digest=:digest AND reason='ROUTE_INVALID'
                        """,
                        key(run).addValue("op", request.operationId())
                                .addValue("digest", codec.fingerprint(request)),
                        Integer.class)
                == 1;
    }

    private static GraphGatewayException denied() {
        return new GraphGatewayException(GraphGatewayException.Code.ROUTE_DENIED);
    }
}

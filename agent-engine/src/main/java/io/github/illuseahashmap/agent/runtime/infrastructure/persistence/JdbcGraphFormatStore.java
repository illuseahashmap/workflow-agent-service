package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphFormatRepair;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Format eligibility and source identity, under the Run lock; shares the existing correction grant.
 */
final class JdbcGraphFormatStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final GraphGatewayCodec codec;

    JdbcGraphFormatStore(NamedParameterJdbcTemplate jdbc, GraphGatewayCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    private MapSqlParameterSource key(AuthorizedRun run) {
        return new MapSqlParameterSource("tenant", run.binding().tenantCode())
                .addValue("run", run.binding().runId());
    }

    void offer(AuthorizedRun run, Invocation validation, String reason) {
        if (!List.of("AGENT_OUTPUT_NOT_JSON", "AGENT_OUTPUT_SCHEMA_INVALID").contains(reason)) {
            return;
        }
        var params = key(run);
        if (jdbc.queryForObject(
                                "SELECT count(*) FROM agent_graph_format_policy WHERE"
                                        + " tenant_code=:tenant AND run_id=:run",
                                params,
                                Integer.class)
                        != 1
                || jdbc.queryForObject(
                                "SELECT count(*) FROM agent_graph_correction WHERE"
                                        + " tenant_code=:tenant AND run_id=:run",
                                params,
                                Integer.class)
                        != 0) {
            return;
        }
        if (!new JdbcGraphCorrectionStore(jdbc, codec).hasBudget(run)) {
            return;
        }
        int ordinal =
                jdbc.queryForObject(
                        """
                        SELECT ordinal FROM agent_graph_v2_model_call WHERE tenant_code=:tenant AND run_id=:run
                          AND candidate_ref=:candidate AND receipt_json IS NOT NULL
                        """,
                        params.addValue("candidate", validation.inputRefs().getFirst()),
                        Integer.class);
        String digest = codec.fingerprint(validation);
        jdbc.update(
                """
                INSERT INTO agent_graph_correction(tenant_code,run_id,operation_id,trigger_operation_id,trigger_digest,source_ordinal,reason)
                VALUES(:tenant,:run,:op,:trigger,:digest,:ordinal,'FORMAT_INVALID')
                """,
                params.addValue(
                                "op",
                                "repair:"
                                        + codec.fingerprint(
                                                List.of(codec.fingerprint(run.binding()), digest)))
                        .addValue("trigger", validation.operationId())
                        .addValue("digest", digest)
                        .addValue("ordinal", ordinal));
    }

    boolean redirect(AuthorizedRun run, Invocation validation) {
        return jdbc.queryForObject(
                        """
                        SELECT count(*) FROM agent_graph_correction WHERE tenant_code=:tenant AND run_id=:run
                          AND trigger_operation_id=:op AND trigger_digest=:digest AND reason='FORMAT_INVALID'
                        """,
                        key(run).addValue("op", validation.operationId())
                                .addValue("digest", codec.fingerprint(validation)),
                        Integer.class)
                == 1;
    }

    GraphFormatRepair.Context context(AuthorizedRun run, Invocation request) {
        if (!request.operationId().startsWith("repair:")) {
            return null;
        }
        require("MODEL".equals(request.kind()));
        var rows =
                jdbc.query(
                        """
                        SELECT m.candidate_ref,m.response_json,v.reason_code
                        FROM agent_graph_correction c
                        JOIN agent_graph_format_policy p USING(tenant_code,run_id)
                        JOIN agent_graph_v2_model_call m ON m.tenant_code=c.tenant_code AND m.run_id=c.run_id AND m.ordinal=c.source_ordinal
                        JOIN agent_graph_v2_validation v ON v.tenant_code=c.tenant_code AND v.run_id=c.run_id AND v.operation_id=c.trigger_operation_id
                        WHERE c.tenant_code=:tenant AND c.run_id=:run AND c.operation_id=:op AND c.reason='FORMAT_INVALID'
                        """,
                        key(run).addValue("op", request.operationId()),
                        (rs, n) ->
                                new GraphFormatRepair.Context(
                                        rs.getString(1),
                                        codec.read(
                                                        rs.getString(2)
                                                                .getBytes(StandardCharsets.UTF_8),
                                                        ModelProviderResponse.class)
                                                .content(),
                                        rs.getString(3)));
        require(rows.size() == 1);
        return rows.getFirst();
    }
}

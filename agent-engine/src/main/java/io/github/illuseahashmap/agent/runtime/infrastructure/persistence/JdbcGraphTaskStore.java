package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphTaskContract;
import io.github.illuseahashmap.agent.runtime.application.port.GraphCognitionPort.ToolFact;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

final class JdbcGraphTaskStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final GraphGatewayCodec codec;

    JdbcGraphTaskStore(NamedParameterJdbcTemplate jdbc, GraphGatewayCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    GraphTaskContract contract(AuthorizedRun run) {
        var rows =
                jdbc.query(
                        "SELECT contract_json,contract_digest FROM agent_graph_task_contract WHERE"
                            + " tenant_code=:tenant AND run_id=:run",
                        Map.of("tenant", run.binding().tenantCode(), "run", run.binding().runId()),
                        (rs, n) -> {
                            var contract =
                                    codec.read(
                                            rs.getString(1).getBytes(StandardCharsets.UTF_8),
                                            GraphTaskContract.class);
                            require(codec.fingerprint(contract).equals(rs.getString(2)));
                            return contract;
                        });
        return rows.isEmpty() ? null : rows.getFirst();
    }

    GraphTaskContract.Report report(AuthorizedRun run, String input, List<ToolFact> facts) {
        var contract = contract(run);
        return contract == null ? null : contract.evaluate(input, facts, codec);
    }
}

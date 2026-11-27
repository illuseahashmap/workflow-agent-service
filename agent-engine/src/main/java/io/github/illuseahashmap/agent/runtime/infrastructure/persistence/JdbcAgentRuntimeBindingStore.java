package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Objects;

@Repository
public class JdbcAgentRuntimeBindingStore
        implements io.github.illuseahashmap.agent.runtime.application.port.AgentRuntimeBinding {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final String artifact;
    private final JdbcGraphExecutionStore executions;

    public JdbcAgentRuntimeBindingStore(
            NamedParameterJdbcTemplate jdbc,
            @Value("${workflow.agent.graph.enabled:false}") boolean enabled,
            @Value("${workflow.agent.graph.artifact-digest:}") String artifact) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.artifact = artifact;
        this.tx =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource())));
        this.executions = new JdbcGraphExecutionStore(jdbc, new GraphGatewayCodec());
    }

    public Configuration read(String tenant, long version) {
        requireVersion(tenant, version, false);
        var rows =
                jdbc.query(
                        "SELECT * FROM agent_version_runtime WHERE tenant_code=:tenant AND"
                                + " agent_version_id=:version",
                        Map.of("tenant", tenant, "version", version),
                        (rs, n) ->
                                new Configuration(
                                        rs.getString("backend"),
                                        rs.getString("artifact_digest"),
                                        rs.getInt("state_schema_version"),
                                        rs.getInt("max_model_calls"),
                                        rs.getInt("max_tool_calls")));
        return rows.isEmpty() ? Configuration.nativeRuntime() : rows.getFirst();
    }

    public Configuration configure(String tenant, long version, Configuration value, String actor) {
        value.validate();
        return tx.execute(
                s -> {
                    String status = requireVersion(tenant, version, true);
                    if (!"DRAFT".equals(status)) {
                        throw new BusinessException(
                                ErrorCode.CONFLICT,
                                "Create a new draft to change execution backend");
                    }
                    if ("LANGGRAPH".equals(value.backend())) {
                        requireAvailable(value);
                    }
                    var params =
                            new MapSqlParameterSource("tenant", tenant)
                                    .addValue("version", version)
                                    .addValue("backend", value.backend())
                                    .addValue("artifact", value.artifactDigest())
                                    .addValue("schema", value.stateSchemaVersion())
                                    .addValue("models", value.maxModelCalls())
                                    .addValue("tools", value.maxToolCalls());
                    jdbc.update(
                            """
                            INSERT INTO agent_version_runtime VALUES(:tenant,:version,:backend,:artifact,:schema,:models,:tools)
                            ON CONFLICT(tenant_code,agent_version_id) DO UPDATE SET backend=excluded.backend,artifact_digest=excluded.artifact_digest,
                            state_schema_version=excluded.state_schema_version,max_model_calls=excluded.max_model_calls,max_tool_calls=excluded.max_tool_calls
                            """,
                            params);
                    params.addValue("actor", actor)
                            .addValue(
                                    "body",
                                    new String(
                                            new GraphGatewayCodec().write(value),
                                            java.nio.charset.StandardCharsets.UTF_8));
                    jdbc.update(
                            "INSERT INTO"
                                + " agent_version_runtime_audit(tenant_code,agent_version_id,actor,configuration)"
                                + " VALUES(:tenant,:version,:actor,CAST(:body AS jsonb))",
                            params);
                    return value;
                });
    }

    @Override
    public void beforeSubmit(String tenant, long version) {
        var config = read(tenant, version);
        if ("LANGGRAPH".equals(config.backend())) {
            requireTransaction();
            requireAvailable(config);
            GraphSubmissionAccess.execute(jdbc, () -> GraphCapacityLock.lock(jdbc));
        }
    }

    @Override
    public void route(String tenant, long version, long run) {
        var config = read(tenant, version);
        if ("LANGGRAPH".equals(config.backend())) {
            requireTransaction();
            requireAvailable(config);
            GraphSubmissionAccess.execute(
                    jdbc,
                    () -> {
                        executions.reserve(
                                tenant,
                                run,
                                config.artifactDigest(),
                                config.maxModelCalls(),
                                config.maxToolCalls(),
                                config.stateSchemaVersion());
                        return null;
                    });
        }
    }

    private void requireAvailable(Configuration config) {
        if (!enabled || !artifact.equals(config.artifactDigest())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Requested graph artifact is not available on this host");
        }
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Graph submission requires a business transaction");
        }
    }

    private String requireVersion(String tenant, long version, boolean lock) {
        var rows =
                jdbc.query(
                        "SELECT status FROM agent_definition_version WHERE tenant_code=:tenant AND"
                                + " id=:version"
                                + (lock ? " FOR UPDATE" : ""),
                        Map.of("tenant", tenant, "version", version),
                        (rs, n) -> rs.getString(1));
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent version does not exist");
        }
        return rows.getFirst();
    }
}

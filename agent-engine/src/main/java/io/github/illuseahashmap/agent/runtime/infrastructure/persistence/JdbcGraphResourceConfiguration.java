package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.InvokeRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.reference;

/** Trusted account aliases, not credentials. Resolution uses platform versions/catalogs, never runtime resource hints. */
public final class JdbcGraphResourceConfiguration implements Function<InvokeRequest, List<String>> {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public JdbcGraphResourceConfiguration(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource())));
        transaction.setTimeout(5);
    }
    public record Resource(String key, int maxCalls) {
        public Resource {
            reference(key);
            require(maxCalls > 0 && maxCalls <= 100000);
        }
    }
    public record Binding(String tenant, String kind, String target, String resource) {
        public Binding {
            reference(tenant);
            reference(target);
            reference(resource);
            require(tenant.length() <= 64 && List.of("PROVIDER", "CONNECTOR", "BUILTIN").contains(kind));
        }
    }
    public record Configuration(List<Resource> resources, List<Binding> bindings) {
        public Configuration {
            require(resources != null && bindings != null && resources.size() <= 1000 && bindings.size() <= 1000);
            resources = List.copyOf(resources);
            bindings = List.copyOf(bindings);
        }
    }
    public Configuration read() {
        return transaction.execute(status -> {
            GraphCapacityLock.lock(jdbc);
            return new Configuration(jdbc.query("SELECT * FROM agent_graph_call_resource ORDER BY resource_key", Map.of(),
                (rs, n) -> new Resource(rs.getString("resource_key"), rs.getInt("max_calls"))),
                jdbc.query("SELECT * FROM agent_graph_resource_binding ORDER BY tenant_code,kind,target_id", Map.of(),
                        (rs, n) -> new Binding(rs.getString("tenant_code"), rs.getString("kind"), rs.getString("target_id"), rs.getString("resource_key"))));
        });
    }
    public void configure(Configuration config) {
        configure(config, "deployment");
    }
    public void configure(Configuration config, String actor) {
        require(actor != null && !actor.isBlank() && actor.length() <= 256);
        String patch = new String(new io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec().write(config), java.nio.charset.StandardCharsets.UTF_8);
        transaction.executeWithoutResult(status -> {
            GraphCapacityLock.lock(jdbc);
            for (Resource resource : config.resources()) {
                jdbc.update("""
                        INSERT INTO agent_graph_call_resource VALUES(:key,:limit)
                        ON CONFLICT(resource_key) DO UPDATE SET max_calls=EXCLUDED.max_calls
                        """, Map.of("key", resource.key(), "limit", resource.maxCalls()));
            }
            for (Binding binding : config.bindings()) {
                var args = Map.of("tenant", binding.tenant(), "kind", binding.kind(), "target", binding.target(), "resource", binding.resource());
                long busy = jdbc.queryForObject("""
                        SELECT count(*) FROM agent_graph_resource_binding b JOIN agent_graph_call_allocation a ON a.resource_key=b.resource_key
                        JOIN agent_graph_call_permit p ON p.token=a.token WHERE b.tenant_code=:tenant AND b.kind=:kind
                        AND b.target_id=:target AND b.resource_key<>:resource AND p.state<>'RELEASED'
                        """, args, Long.class);
                if (busy != 0) { throw new io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException(
                        io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.RESOURCE_BUSY); }
                jdbc.update("""
                        INSERT INTO agent_graph_resource_binding VALUES(:tenant,:kind,:target,:resource)
                        ON CONFLICT(tenant_code,kind,target_id) DO UPDATE SET resource_key=EXCLUDED.resource_key
                        """, args);
            }
            // Keep the management snapshot bounded and readable, not merely each submitted patch.
            require(jdbc.queryForObject("SELECT count(*) FROM agent_graph_call_resource", Map.of(), Long.class) <= 1000);
            require(jdbc.queryForObject("SELECT count(*) FROM agent_graph_resource_binding", Map.of(), Long.class) <= 1000);
            jdbc.update("INSERT INTO agent_graph_resource_audit(actor,patch_json) VALUES(:actor,CAST(:patch AS jsonb))", Map.of("actor", actor, "patch", patch));
        });
    }
    @Override public List<String> apply(InvokeRequest request) {
        String tenant = request.binding().tenantCode();
        String kind;
        String target;
        if ("MODEL".equals(request.request().kind())) {
            kind = "PROVIDER";
            var rows = jdbc.query("SELECT provider_id FROM agent_definition_version WHERE tenant_code=:tenant AND id=:version AND status='PUBLISHED'",
                    Map.of("tenant", tenant, "version", request.binding().agentVersionId()), (rs, n) -> rs.getString(1));
            require(rows.size() == 1 && rows.getFirst() != null);
            target = rows.getFirst();
        } else {
            require("TOOL".equals(request.request().kind()));
            String tool = request.request().tool().toolCode();
            if (tool.startsWith("mcp:")) {
                kind = "CONNECTOR";
                var rows = jdbc.query("""
                        SELECT v.connector_id FROM mcp_tool_snapshot s
                        JOIN mcp_tool_catalog_version c ON c.id=s.catalog_version_id AND c.tenant_code=s.tenant_code
                        JOIN mcp_connector_version v ON v.id=c.connector_version_id AND v.tenant_code=c.tenant_code
                        WHERE s.tenant_code=:tenant AND ('mcp:'||s.id||':'||s.tool_name)=:tool AND c.status='PUBLISHED'
                        """, Map.of("tenant", tenant, "tool", tool), (rs, n) -> rs.getString(1));
                require(rows.size() == 1);
                target = rows.getFirst();
            } else {
                kind = "BUILTIN";
                target = tool;
            }
        }
        var rows = jdbc.query("SELECT resource_key FROM agent_graph_resource_binding WHERE tenant_code=:tenant AND kind=:kind AND target_id=:target",
                Map.of("tenant", tenant, "kind", kind, "target", target), (rs, n) -> rs.getString(1));
        require(rows.size() == 1);
        return rows;
    }
}

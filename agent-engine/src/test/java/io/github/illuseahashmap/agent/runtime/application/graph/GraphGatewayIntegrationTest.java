package io.github.illuseahashmap.agent.runtime.application.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Binding;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.agent.runtime.application.port.GraphReferencePort;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcAgentToolGovernanceRepository;
import io.github.illuseahashmap.agent.runtime.interfaces.rest.GraphGatewayProtocol;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Real Python HTTP client -> Java registry -> PostgreSQL audit. No paid model/MCP call. */
@EnabledIfEnvironmentVariable(named = "WORKFLOW_LANGGRAPH_JDBC_URL", matches = ".+")
class GraphGatewayIntegrationTest {
    private static final String ARGUMENTS = "{\"word\":\"procurement\"}";
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"word\":{\"type\":\"string\"}},\"required\":[\"word\"]}";

    @Test void pythonRequestsReuseTheRealPostgresToolLedger() throws Exception {
        String url = System.getenv("WORKFLOW_LANGGRAPH_JDBC_URL");
        assertThat(url).doesNotContain("?");
        String user = System.getenv().getOrDefault("WORKFLOW_LANGGRAPH_JDBC_USER", "postgres");
        String password = System.getenv().getOrDefault("WORKFLOW_LANGGRAPH_JDBC_PASSWORD", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        String schema = "graph_gateway_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            var ds = new DriverManagerDataSource(url + "?currentSchema=" + schema, user, password);
            exercise(new NamedParameterJdbcTemplate(ds));
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private void exercise(NamedParameterJdbcTemplate named) throws Exception {
        JdbcTemplate db = named.getJdbcTemplate();
        // Only the existing registry's table contract; this is not a Flyway/RLS acceptance test.
        db.execute("""
                CREATE TABLE agent_tool_definition (
                  tool_code TEXT PRIMARY KEY, tool_name TEXT, input_schema TEXT, read_only BOOLEAN,
                  capability_type TEXT, risk_level TEXT, side_effect BOOLEAN, enabled BOOLEAN);
                CREATE TABLE agent_tool_tenant_grant (
                  tenant_code TEXT, tool_code TEXT, enabled BOOLEAN, PRIMARY KEY(tenant_code, tool_code));
                CREATE TABLE agent_tool_execution_audit (
                  id BIGSERIAL PRIMARY KEY, tenant_code TEXT, tool_code TEXT, idempotency_key TEXT,
                  arguments_hash TEXT, status TEXT, output_snapshot TEXT, error_code TEXT,
                  trace_id TEXT, created_at TIMESTAMPTZ, claim_owner TEXT, lease_expires_at TIMESTAMPTZ,
                  fencing_token BIGINT DEFAULT 0, UNIQUE(tenant_code, tool_code, idempotency_key));
                CREATE TABLE fixture_reference (
                  reference TEXT PRIMARY KEY, binding_digest TEXT, operation_id TEXT, tool_code TEXT, body TEXT);
                CREATE TABLE fixture_receipt (
                  reference TEXT PRIMARY KEY, binding_digest TEXT, operation_id TEXT, ledger_key TEXT, digest TEXT);
                """);
        db.update("INSERT INTO agent_tool_definition VALUES ('lookup','lookup',?,true,'NATIVE','LOW',false,true)", SCHEMA);
        db.update("INSERT INTO agent_tool_tenant_grant VALUES ('tenant-a','lookup',true)");
        var codec = new GraphGatewayCodec();
        for (long id : List.of(20001L, 20002L, 20003L)) {
            db.update("INSERT INTO fixture_reference VALUES (?,?, 'tool:1','lookup',?)",
                    "args:" + id + ":1", codec.fingerprint(binding(id)), ARGUMENTS);
        }
        GraphRunAuthorityPort authority = (token, submitted, generation) -> {
            if (!("fixture:" + submitted.runId()).equals(token) || generation != 1
                    || !List.of(20001L, 20002L, 20003L).contains(submitted.runId())
                    || !binding(submitted.runId()).equals(submitted)) {
                throw new GraphGatewayException(GraphGatewayException.Code.AUTHORITY_DENIED);
            }
            return new GraphRunAuthorityPort.AuthorizedRun(submitted, generation, "reviewer", null, "[\"lookup\"]", null);
        };
        GraphReferencePort references = new GraphReferencePort() {
            public Arguments arguments(GraphRunAuthorityPort.AuthorizedRun run, Invocation request) {
                return db.query("SELECT * FROM fixture_reference WHERE reference=?",
                        (rs, row) -> new Arguments(rs.getString("binding_digest"), rs.getString("operation_id"),
                                rs.getString("tool_code"), GraphGatewayCodec.sha256(rs.getString("body")), rs.getString("body")),
                        request.tool().argumentsRef()).stream().findFirst().orElse(null);
            }
            public String recordReceipt(GraphRunAuthorityPort.AuthorizedRun run, Invocation request, String key, String digest) {
                String bindingDigest = codec.fingerprint(run.binding());
                String ref = "receipt:" + GraphGatewayCodec.sha256(bindingDigest + ":" + key + ":" + digest);
                db.update("INSERT INTO fixture_receipt VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                        ref, bindingDigest, request.operationId(), key, digest);
                return ref;
            }
        };
        var count = new AtomicInteger();
        AgentTool tool = new AgentTool() {
            public String name() { return "lookup"; }
            public String inputSchema() { return SCHEMA; }
            public Result execute(Request request) {
                count.incrementAndGet();
                if (request.runId() == 20003) {
                    try { Thread.sleep(150); }
                    catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                }
                return new Result("{\"count\":" + request.arguments().get("word").toString().length() + "}", request.idempotencyKey());
            }
        };
        var jdbc = new JdbcAgentToolGovernanceRepository(named);
        var mapper = new ObjectMapper();
        var registry = new AgentToolRegistry(List.of(tool), jdbc, jdbc, new AgentOutputSchemaValidator(mapper), mapper);
        var protocol = new GraphGatewayProtocol(new GraphToolGateway(authority, references, registry, codec), codec);
        var dropped = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        var workers = Executors.newFixedThreadPool(8);
        server.setExecutor(workers);
        server.createContext(GraphGatewayProtocol.PREFIX, exchange -> {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readNBytes(GraphGatewayCodec.MAX_WIRE_BYTES + 1);
                var result = protocol.exchange(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"), body);
                if (exchange.getRequestURI().getPath().endsWith("/invoke") && result.status() == 200
                        && dropped.compareAndSet(false, true)) { return; }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(result.status(), result.body().length);
                exchange.getResponseBody().write(result.body());
            }
        });
        server.start();
        try {
            Path runtime = Path.of("../agent-runtime-langgraph").toAbsolutePath().normalize();
            Path log = Files.createTempFile("graph-gateway-probe-", ".log");
            var command = new ProcessBuilder("uv", "run", "--frozen", "python", "tests/java_gateway_probe.py");
            command.directory(runtime.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            command.environment().put("GRAPH_FIXTURE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
            command.environment().put("GRAPH_FIXTURE_ARGUMENTS_DIGEST", GraphGatewayCodec.sha256(ARGUMENTS));
            Process process = command.start();
            try {
                assertThat(process.waitFor(45, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).withFailMessage(Files.readString(log, StandardCharsets.UTF_8)).isZero();
                assertThat(Files.readString(log)).contains("JAVA_GATEWAY_PROBE_OK");
            } finally {
                if (process.isAlive()) { process.destroyForcibly(); }
            }
            assertThat(count).hasValue(3);
            assertThat(db.queryForObject("SELECT count(*) FROM agent_tool_execution_audit WHERE status='SUCCEEDED'", Integer.class)).isEqualTo(3);
            assertThat(db.queryForObject("SELECT count(*) FROM fixture_receipt", Integer.class)).isEqualTo(3);
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
    }
    private Binding binding(long id) {
        return new Binding("tenant-a", id, 3, "a".repeat(64), 1, "input:purchase:" + id,
                "subject:reviewer", 4_000_000_000_000L, 10, 20);
    }
}

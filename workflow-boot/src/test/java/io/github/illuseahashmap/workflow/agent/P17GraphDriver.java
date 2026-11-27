package io.github.illuseahashmap.workflow.agent;

import static io.github.illuseahashmap.workflow.agent.P17Pilot.JSON;
import static io.github.illuseahashmap.workflow.agent.P17Pilot.TENANT;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.illuseahashmap.agent.provider.application.ModelProviderRegistry;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderPort;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.agent.runtime.application.checkpoint.CheckpointContextPolicy;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphCandidateGate;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphCognitionGateway;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphCompletionService;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphToolGateway;
import io.github.illuseahashmap.agent.runtime.application.port.GraphDispatchPort.Observation;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCognitionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCompletionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphExecutionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphReferenceStore;
import io.github.illuseahashmap.agent.runtime.interfaces.rest.GraphGatewayProtocol;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Only the transport/model fixture is local; all graph decisions and durable authority are real.
 */
final class P17GraphDriver {
    static void execute(
            P17Pilot p,
            NamedParameterJdbcTemplate jdbc,
            AgentRunEventPublisherAdapter publisher,
            long runId,
            boolean joint,
            Path result)
            throws Exception {
        var codec = new GraphGatewayCodec();
        var executions = new JdbcGraphExecutionStore(jdbc, codec);
        executions.reserve(TENANT, runId, "a".repeat(64), 30, 8, 2);
        var claim = executions.claim(TENANT, "p17-graph", 120).orElseThrow();
        assertThat(claim.binding().runId()).isEqualTo(runId);
        var budget = new CheckpointContextPolicy(40000);
        var tools =
                new GraphToolGateway(
                        executions,
                        new JdbcGraphReferenceStore(jdbc, executions, codec),
                        p.registry,
                        codec);
        var cognition =
                new GraphCognitionGateway(
                        tools,
                        executions,
                        new JdbcGraphCognitionStore(jdbc, executions, codec),
                        p.versions,
                        p.providers,
                        p.credentials,
                        new ModelProviderRegistry(List.of(new Scripted(p, joint))),
                        p.registry,
                        p.validator,
                        budget,
                        JSON,
                        codec);
        jdbc.update(
                "INSERT INTO agent_graph_call_resource VALUES('p17:fixture',16) ON CONFLICT DO"
                    + " NOTHING",
                Map.of());
        var governed =
                new io.github.illuseahashmap.agent.runtime.application.graph.GraphCapacityGateway(
                        cognition,
                        executions,
                        new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                                .JdbcGraphCallCapacityStore(jdbc, executions, codec),
                        request -> List.of("p17:fixture"),
                        codec);
        var observed =
                new io.github.illuseahashmap.agent.runtime.application.port.GraphGatewayPort() {
                    public GraphGatewayContract.Authorized authorize(
                            String capability, GraphGatewayContract.AuthorizationRequest request) {
                        try {
                            return governed.authorize(capability, request);
                        } catch (RuntimeException failure) {
                            failure.printStackTrace();
                            throw failure;
                        } catch (AssertionError failure) {
                            throw new IllegalStateException("Pilot assertion failed", failure);
                        }
                    }

                    public GraphGatewayContract.Reply invoke(
                            String capability, GraphGatewayContract.InvokeRequest request) {
                        try {
                            return governed.invoke(capability, request);
                        } catch (GraphGatewayException control) {
                            throw control;
                        } catch (RuntimeException failure) {
                            failure.printStackTrace();
                            throw failure;
                        } catch (AssertionError failure) {
                            throw new IllegalStateException("Pilot assertion failed", failure);
                        }
                    }
                };
        var protocol = new GraphGatewayProtocol(observed, codec);
        var server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        var workers = Executors.newFixedThreadPool(4);
        server.setExecutor(workers);
        server.createContext(
                "/internal/agent-runtime/v2",
                exchange -> {
                    try (exchange) {
                        var reply =
                                protocol.exchange(
                                        exchange.getRequestMethod(),
                                        exchange.getRequestURI().getPath(),
                                        exchange.getRequestHeaders().getFirst("Authorization"),
                                        exchange.getRequestBody()
                                                .readNBytes(GraphGatewayCodec.MAX_WIRE_BYTES + 1));
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(reply.status(), reply.body().length);
                        exchange.getResponseBody().write(reply.body());
                    }
                });
        server.start();
        try {
            Path log = result.resolveSibling(result.getFileName() + ".log");
            var command =
                    new ProcessBuilder(
                                    "uv", "run", "--frozen", "python", "tests/p17_graph_probe.py")
                            .directory(Path.of("../agent-runtime-langgraph").toFile())
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile());
            var env = command.environment();
            env.put(
                    "P17_BINDING",
                    new String(
                            codec.write(claim.binding()), java.nio.charset.StandardCharsets.UTF_8));
            env.put("P17_GENERATION", Long.toString(claim.generation()));
            env.put("P17_CAPABILITY", claim.capability());
            env.put("P17_URL", "http://127.0.0.1:" + server.getAddress().getPort());
            env.put("P17_RESULT", result.toAbsolutePath().toString());
            var ds = (DriverManagerDataSource) jdbc.getJdbcTemplate().getDataSource();
            var uri = URI.create(ds.getUrl().substring(5));
            env.put(
                    "P17_DATABASE",
                    JSON.writeValueAsString(
                            Map.of(
                                    "host",
                                    uri.getHost(),
                                    "port",
                                    uri.getPort(),
                                    "dbname",
                                    uri.getPath().substring(1),
                                    "user",
                                    ds.getUsername(),
                                    "password",
                                    ds.getPassword())));
            var process = command.start();
            try {
                assertThat(process.waitFor(90, TimeUnit.SECONDS))
                        .as("Graph probe deadline")
                        .isTrue();
                assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
            var outcome = JSON.readTree(result.toFile());
            assertThat(outcome.has("error")).as(outcome.toString()).isFalse();
            var view = outcome.required("view");
            if (!"STOPPED".equals(view.path("status").asText())) {
                assertThat(
                                executions.acknowledge(
                                        claim,
                                        new Observation(
                                                view.path("status").asText(),
                                                view.path("checkpoint_id").asText(),
                                                view.path("candidate_ref").asText(null),
                                                view.path("validation_ref").asText(null))))
                        .isTrue();
            } else {
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM agent_graph_run_stop WHERE"
                                            + " tenant_code=:tenant AND run_id=:run",
                                        Map.of("tenant", TENANT, "run", runId),
                                        Integer.class))
                        .isEqualTo(1);
            }
            var completion =
                    new GraphCompletionService(
                            new JdbcGraphCompletionStore(jdbc, executions, codec),
                            p.versions,
                            p.registry,
                            new GraphCandidateGate(p.validator, JSON, budget),
                            publisher,
                            JSON);
            assertThat(completion.completeNext()).isTrue();
            assertThat(completion.completeNext()).isFalse();
        } finally {
            server.stop(0);
            workers.shutdownNow();
            if ("RUNNING"
                    .equals(
                            jdbc.getJdbcTemplate()
                                    .queryForObject(
                                            "SELECT status FROM agent_run WHERE id=?",
                                            String.class,
                                            runId))) {
                executions.revoke(TENANT, runId); // Test cleanup of incomplete observations only.
            }
        }
    }

    private static final class Scripted implements ModelProviderPort {
        private final P17Pilot p;
        private final boolean joint;
        private String citation;

        Scripted(P17Pilot p, boolean joint) {
            this.p = p;
            this.joint = joint;
        }

        public AgentProviderType providerType() {
            return AgentProviderType.MOCK;
        }

        public ModelProviderResponse invoke(ModelProviderRequest request) {
            assertThat(p.revokedLocators).as("No model after evidence revocation").isEmpty();
            try {
                return new ModelProviderResponse(
                        decide(request), "scripted-graph", "isolated", "stop", 1, 1, 0, 1);
            } catch (java.io.IOException invalid) {
                throw new AssertionError(invalid);
            }
        }

        private String decide(ModelProviderRequest request) throws java.io.IOException {
            if (request.systemPrompt().contains("Choose a route using only")) {
                return JSON.writeValueAsString(
                        Map.of(
                                "schemaVersion",
                                "1",
                                "mode",
                                joint ? "RAG_MCP" : "RAG",
                                "reason",
                                "verify",
                                "requestedTools",
                                joint
                                        ? List.of("knowledge_search", p.toolCode)
                                        : List.of("knowledge_search"),
                                "steps",
                                List.of("verify")));
            }

            if (request.systemPrompt().contains("Merge prior notes")) {
                return "Unverified notes. Read original receipts before deciding.";
            }

            String input = request.userInput();
            String indexMarker = "Original result index (READ_RESULT retrieves fields):\n";
            JsonNode index;
            if (!input.contains(indexMarker)) {
                String marker =
                        "Committed tool observations (reference, tool, arguments, original"
                                + " output):\n";
                var originals =
                        JSON.readTree(input.substring(input.indexOf(marker) + marker.length()));
                if (originals.isEmpty()) {
                    return tool(
                            "knowledge_search",
                            Map.of(
                                    "query",
                                    "没有该采购单的实时预算、审批、报价有效期、准入和签署权限记录，能否断言可以下单？",
                                    "maxResults",
                                    3));
                }

                return read(
                        originals.get(0).required("receipt_ref").asText(),
                        "/evidence/0/reference/locator");
            }
            String indexText = input.substring(input.indexOf(indexMarker) + indexMarker.length());
            index = JSON.readTree(indexText.substring(0, indexText.indexOf('\n')));
            String readMarker = "Requested original segment:\n";
            String readText = input.substring(input.indexOf(readMarker) + readMarker.length());
            JsonNode read = readText.isBlank() ? JSON.nullNode() : JSON.readTree(readText);
            String policy = null;
            String records = null;
            for (var entry : index) {
                if (entry.path("tool").asText().equals("knowledge_search")) {
                    policy = entry.path("reference").asText();
                }

                if (entry.path("tool").asText().equals(p.toolCode)) {
                    records = entry.path("reference").asText();
                }
            }
            if (citation == null) {
                if (!read.path("source").asText().equals(policy)) {
                    return read(policy, "/evidence/0/reference/locator");
                }

                assertThat(read.path("status").asText()).isEqualTo("OK");
                citation = read.required("content").asText();
            }
            JsonNode receipt = JSON.nullNode();
            if (joint) {
                if (records == null) {
                    return tool(p.toolCode, Map.of("orderId", "P-17"));
                }

                if (!read.path("source").asText().equals(records)) {
                    return read(records, "");
                }

                receipt = JSON.readTree(read.required("content").asText());
            }
            boolean valid = "P-17".equals(receipt.path("subject").asText());
            boolean met = true;
            for (String fact :
                    List.of(
                            "approval",
                            "budget",
                            "quoteValidity",
                            "admission",
                            "signingAuthority")) {
                met &= "MET".equals(receipt.path("facts").path(fact).asText());
            }
            return JSON.writeValueAsString(
                    Map.of(
                            "action",
                            valid ? "ANSWER" : "ABSTAIN",
                            "decision",
                            !valid
                                    ? "MISSING_LIVE_RECORDS"
                                    : met ? "ORDER_READY" : "ORDER_NOT_READY",
                            "citations",
                            List.of(citation)));
        }

        private String tool(String name, Map<String, Object> args) throws java.io.IOException {
            return JSON.writeValueAsString(
                    Map.of("action", "TOOL_CALL", "tool", name, "arguments", args));
        }

        private String read(String reference, String pointer) throws java.io.IOException {
            return JSON.writeValueAsString(
                    Map.of("action", "READ_RESULT", "reference", reference, "pointer", pointer));
        }
    }
}

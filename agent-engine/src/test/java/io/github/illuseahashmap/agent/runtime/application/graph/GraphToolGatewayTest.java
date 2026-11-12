package io.github.illuseahashmap.agent.runtime.application.graph;

import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.agent.runtime.application.port.AgentToolExecutionAuditRepository;
import io.github.illuseahashmap.agent.runtime.application.port.AgentToolPolicyRepository;
import io.github.illuseahashmap.agent.runtime.application.port.GraphReferencePort;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Binding;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.InvokeRequest;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.ToolIntent;
import io.github.illuseahashmap.agent.runtime.interfaces.rest.GraphGatewayProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphToolGatewayTest {
    private final GraphGatewayCodec codec = new GraphGatewayCodec();
    private final Binding binding = new Binding("tenant-a", 20001, 3, "a".repeat(64), 1,
            "input:purchase:20001", "subject:reviewer", 4_000_000_000_000L, 10, 20);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicLong ticker = new AtomicLong();
    private boolean revoked;
    private boolean revokeDuringCall;
    private boolean receiptFailure;
    private boolean referenceFailure;
    private boolean toolAllowed = true;
    private boolean revokeDuringReceipt;
    private boolean transientToolFailure;
    private String versionTools = "[\"lookup\"]";
    private String argumentJson = "{\"word\":\"procurement\"}";
    private String boundOperation = "tool:1";
    private long referenceDelay;
    private final Map<String, AgentToolExecutionAuditRepository.Audit> ledger = new HashMap<>();

    private InvokeRequest request() {
        return new InvokeRequest(binding, 1, new Invocation("TOOL", "tool:1", List.of(),
                new ToolIntent("lookup", "args:20001:1", GraphGatewayCodec.sha256(argumentJson))));
    }
    private GraphGatewayProtocol protocol() {
        GraphRunAuthorityPort authority = (token, submitted, generation) -> {
            if (revoked || !token.equals("test-session") || generation != 1 || !binding.equals(submitted)) {
                throw new GraphGatewayException(GraphGatewayException.Code.AUTHORITY_DENIED);
            }
            return new GraphRunAuthorityPort.AuthorizedRun(binding, 1, "reviewer", "flow-1", versionTools, null);
        };
        GraphReferencePort references = new GraphReferencePort() {
            public Arguments arguments(GraphRunAuthorityPort.AuthorizedRun run, Invocation request) {
                ticker.addAndGet(referenceDelay);
                return new Arguments(referenceFailure ? "f".repeat(64) : codec.fingerprint(binding),
                        boundOperation, "lookup", GraphGatewayCodec.sha256(argumentJson), argumentJson);
            }
            public String recordReceipt(GraphRunAuthorityPort.AuthorizedRun run, Invocation request,
                                        String key, String digest) {
                if (receiptFailure) { throw new IllegalStateException("secret diagnostic must not escape"); }
                if (revokeDuringReceipt) { revoked = true; }
                assertThat(key).isEqualTo("20001:tool:1:lookup");
                return "receipt:" + GraphGatewayCodec.sha256(key + digest);
            }
        };
        AgentTool tool = new AgentTool() {
            public String name() { return "lookup"; }
            public String inputSchema() {
                return "{\"type\":\"object\",\"properties\":{\"word\":{\"type\":\"string\"}},\"required\":[\"word\"]}";
            }
            public Result execute(Request request) {
                assertThat(request.tenantCode()).isEqualTo(binding.tenantCode());
                assertThat(request.runId()).isEqualTo(binding.runId());
                assertThat(request.agentVersionId()).isEqualTo(3);
                assertThat(request.principalId()).isEqualTo("reviewer");
                assertThat(request.timeout()).isPositive().isLessThanOrEqualTo(java.time.Duration.ofSeconds(120));
                calls.incrementAndGet();
                if (transientToolFailure) {
                    throw new io.github.illuseahashmap.agent.runtime.application.AgentExecutionException(
                            new io.github.illuseahashmap.agent.runtime.domain.AgentFailure("MCP_TIMEOUT",
                                    io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory.EXECUTION_UNEXPECTED,
                                    true, io.github.illuseahashmap.agent.runtime.domain.ResultStatus.FAILED, "private diagnostic"));
                }
                if (revokeDuringCall) { revoked = true; }
                return new Result("{\"count\":11}", request.idempotencyKey());
            }
        };
        var audits = new AgentToolExecutionAuditRepository() {
            public Optional<Audit> findByIdempotencyKey(String tenant, String tool, String key) {
                return Optional.ofNullable(ledger.get(key));
            }
            public void save(Audit row) { ledger.put(row.idempotencyKey(), row); }
        };
        var mapper = new ObjectMapper();
        AgentToolPolicyRepository policy = (tenant, code) -> toolAllowed
                ? AgentToolPolicyRepository.ALLOW_ALL.findAuthorized(tenant, code) : Optional.empty();
        var registry = new AgentToolRegistry(List.of(tool), policy, audits,
                new AgentOutputSchemaValidator(mapper), mapper);
        var gateway = new GraphToolGateway(authority, references, registry, codec,
                Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC), ticker::get);
        return new GraphGatewayProtocol(gateway, codec);
    }
    private GraphGatewayProtocol.Response invoke(GraphGatewayProtocol protocol, InvokeRequest request) {
        return protocol.exchange("POST", GraphGatewayProtocol.PREFIX + "/invoke", "Bearer test-session", codec.write(request));
    }

    @Test void reusesToolReceiptAfterGatewayReplyWasLost() {
        var protocol = protocol();
        receiptFailure = true;
        var first = invoke(protocol, request());
        assertThat(first.status()).isEqualTo(503);
        assertThat(new String(first.body())).doesNotContain("secret");
        assertThat(ledger.get("20001:tool:1:lookup").status()).isEqualTo("SUCCEEDED");
        receiptFailure = false;
        var replay = invoke(protocol(), request());
        assertThat(replay.status()).isEqualTo(200);
        assertThat(invoke(protocol(), request()).body()).isEqualTo(replay.body());
        assertThat(calls).hasValue(1);
    }
    @Test void revocationAfterToolSuccessPreservesLedgerButDoesNotReturnSuccess() {
        revokeDuringCall = true;
        assertThat(invoke(protocol(), request()).status()).isEqualTo(403);
        assertThat(ledger.get("20001:tool:1:lookup").status()).isEqualTo("SUCCEEDED");
        assertThat(invoke(protocol(), request()).status()).isEqualTo(403);
        assertThat(calls).hasValue(1);
    }
    @Test void foreignReferenceFailsBeforeExternalCall() {
        referenceFailure = true;
        assertThat(invoke(protocol(), request()).status()).isEqualTo(403);
        assertThat(calls).hasValue(0);
    }
    @Test void referenceCannotMoveToAnotherLogicalStep() {
        boundOperation = "tool:2";
        assertThat(invoke(protocol(), request()).status()).isEqualTo(403);
        assertThat(calls).hasValue(0);
    }
    @Test void schemaFailureDoesNotInvokeTool() {
        argumentJson = "{\"word\":42}";
        assertThat(invoke(protocol(), request()).status()).isEqualTo(422);
        assertThat(calls).hasValue(0);
    }
    @Test void changedArgumentsCannotReexecuteStableOperation() {
        assertThat(invoke(protocol(), request()).status()).isEqualTo(200);
        argumentJson = "{\"word\":\"different\"}";
        assertThat(invoke(protocol(), request()).status()).isEqualTo(422);
        assertThat(calls).hasValue(1);
    }
    @Test void referenceLookupConsumesMonotonicBudget() {
        referenceDelay = java.time.Duration.ofSeconds(121).toNanos();
        assertThat(invoke(protocol(), request()).status()).isEqualTo(409);
        assertThat(calls).hasValue(0);
    }
    @Test void versionToolIntersectionIsStillEnforced() {
        versionTools = "[]";
        assertThat(invoke(protocol(), request()).status()).isEqualTo(422);
        assertThat(calls).hasValue(0);
    }
    @Test void revokedToolGrantBlocksCachedReceipt() {
        assertThat(invoke(protocol(), request()).status()).isEqualTo(200);
        toolAllowed = false;
        assertThat(invoke(protocol(), request()).status()).isEqualTo(422);
        assertThat(calls).hasValue(1);
    }
    @Test void receiptPersistenceCannotHideRevocation() {
        revokeDuringReceipt = true;
        assertThat(invoke(protocol(), request()).status()).isEqualTo(403);
        assertThat(calls).hasValue(1);
    }
    @Test void typedTransientToolFailuresAreNotTurnedIntoPermanentRejections() {
        transientToolFailure = true;
        var response = invoke(protocol(), request());
        assertThat(response.status()).isEqualTo(503);
        assertThat(new String(response.body())).contains("TOOL_TRANSIENT").doesNotContain("private");
        assertThat(calls).hasValue(1);
    }
    @Test void missingCapabilityIsNotReplacedByCookieOrServiceLogin() {
        var response = protocol().exchange("POST", GraphGatewayProtocol.PREFIX + "/invoke", null, codec.write(request()));
        assertThat(response.status()).isEqualTo(403);
        assertThat(calls).hasValue(0);
    }
    @Test void unsupportedModelOperationFailsClosed() {
        var model = new InvokeRequest(binding, 1, new Invocation("MODEL", "model:1", List.of(binding.inputRef()), null));
        assertThat(invoke(protocol(), model).status()).isEqualTo(422);
        assertThat(calls).hasValue(0);
    }
    @ParameterizedTest @ValueSource(strings = {"null", "{}", "{\"binding\":null,\"generation\":1}",
            "{\"x\":1,\"x\":2}", "{} {}"})
    void malformedJsonFailsClosed(String json) {
        var response = protocol().exchange("POST", GraphGatewayProtocol.PREFIX + "/authorize", "Bearer test-session",
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(response.status()).isEqualTo(400);
    }
    @Test void scalarCoercionAndUnknownFieldsAreRejected() {
        String json = new String(codec.write(request()), java.nio.charset.StandardCharsets.UTF_8);
        for (String invalid : List.of(json.replace("\"run_id\":20001", "\"run_id\":\"20001\""),
                json.replace("\"run_id\":20001", "\"run_id\":20001.5"),
                json.replace("\"generation\":1", "\"generation\":1,\"api_key\":\"secret\""))) {
            assertThatThrownBy(() -> codec.read(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8), InvokeRequest.class))
                    .isInstanceOf(GraphGatewayException.class);
        }
    }
}

package io.github.illuseahashmap.agent.runtime.application.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;
import io.github.illuseahashmap.agent.definition.domain.AgentFailurePolicy;
import io.github.illuseahashmap.agent.definition.domain.AgentVersionStatus;
import io.github.illuseahashmap.agent.provider.application.ModelProviderRegistry;
import io.github.illuseahashmap.agent.provider.application.port.AgentCredentialResolver;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderPort;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry;
import io.github.illuseahashmap.agent.runtime.application.port.AgentExecutor;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class PlatformAgentExecutorTest {

    @Test
    void runsBoundedPlanAndAnswerPhasesBeforeReturningFinalContent() {
        AgentCredentialResolver credentials = mock(AgentCredentialResolver.class);
        ModelProviderPort adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        when(adapter.invoke(any())).thenReturn(
                response(route()), response("{\"answer\":\"done\"}"));
        PlatformAgentExecutor executor = new PlatformAgentExecutor(
                credentials, new ModelProviderRegistry(List.of(adapter)),
                new AgentOutputSchemaValidator(new ObjectMapper()));

        AgentExecutor.Result result = executor.execute(new AgentExecutor.Command(
                "tenant-a", version(), provider(), "hello", Duration.ofSeconds(10), "trace-1"));

        assertThat(result.modelResponse().content()).isEqualTo("{\"answer\":\"done\"}");
        ArgumentCaptor<io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest> requests =
                ArgumentCaptor.forClass(io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest.class);
        verify(adapter, atLeast(2)).invoke(requests.capture());
        assertThat(requests.getAllValues().getFirst().timeout())
                .isGreaterThan(Duration.ofSeconds(9))
                .isLessThanOrEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void executesOnlyARegisteredToolRequestedByTheModel() {
        AgentCredentialResolver credentials = mock(AgentCredentialResolver.class);
        ModelProviderPort adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        when(adapter.invoke(any())).thenReturn(
                response(route("lookup")),
                response("{\"action\":\"TOOL_CALL\",\"tool\":\"lookup\",\"arguments\":{\"id\":\"A-1\"}}"),
                response("{\"answer\":\"found\"}"));
        AgentTool tool = new AgentTool() {
            @Override
            public String name() {
                return "lookup";
            }

            @Override
            public Result execute(Request request) {
                assertThat(request.tenantCode()).isEqualTo("tenant-a");
                assertThat(request.arguments()).containsEntry("id", "A-1");
                return new Result("record found", "lookup-A-1");
            }
        };
        PlatformAgentExecutor executor = new PlatformAgentExecutor(
                credentials, new ModelProviderRegistry(List.of(adapter)),
                new AgentOutputSchemaValidator(new ObjectMapper()),
                new AgentToolRegistry(List.of(tool)), new ObjectMapper(), 3);

        AgentExecutor.Result result = executor.execute(new AgentExecutor.Command(
                "tenant-a", version(), provider(), "hello", Duration.ofSeconds(10), "trace-1"));

        assertThat(result.modelResponse().content()).isEqualTo("{\"answer\":\"found\"}");
        assertThat(result.steps()).extracting(AgentExecutor.StepResult::stepType)
                .contains("ROUTE", "TOOL_CALL", "VALIDATION");
    }

    @Test
    void treatsMissingToolArgumentsAsEmptyRuntimeOwnedArguments() {
        AgentCredentialResolver credentials = mock(AgentCredentialResolver.class);
        ModelProviderPort adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        when(adapter.invoke(any())).thenReturn(
                response(route("context")),
                response("{\"action\":\"TOOL_CALL\",\"tool\":\"context\"}"),
                response("{\"answer\":\"done\"}"));
        AgentTool tool = new AgentTool() {
            @Override
            public String name() {
                return "context";
            }

            @Override
            public Result execute(Request request) {
                assertThat(request.arguments()).isEmpty();
                return new Result("context", "context-1");
            }
        };
        PlatformAgentExecutor executor = new PlatformAgentExecutor(
                credentials, new ModelProviderRegistry(List.of(adapter)),
                new AgentOutputSchemaValidator(new ObjectMapper()),
                new AgentToolRegistry(List.of(tool)), new ObjectMapper(), 3);

        AgentExecutor.Result result = executor.execute(new AgentExecutor.Command(
                "tenant-a", version(), provider(), "hello", Duration.ofSeconds(10), "trace-1"));

        assertThat(result.modelResponse().content()).isEqualTo("{\"answer\":\"done\"}");
    }

    @Test
    void repairsMalformedToolCallWithOneBoundedProtocolRound() {
        AgentCredentialResolver credentials = mock(AgentCredentialResolver.class);
        ModelProviderPort adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        when(adapter.invoke(any())).thenReturn(
                response(route("lookup")),
                response("{\"action\":\"TOOL_CALL\"}"),
                response("{\"action\":\"TOOL_CALL\",\"tool\":\"lookup\",\"arguments\":{}}"),
                response("{\"answer\":\"done\"}"));
        AgentTool tool = new AgentTool() {
            @Override
            public String name() {
                return "lookup";
            }

            @Override
            public Result execute(Request request) {
                assertThat(request.arguments()).isEmpty();
                return new Result("record found", "lookup-1");
            }
        };
        AgentTool hidden = new AgentTool() {
            @Override
            public String name() {
                return "hidden";
            }

            @Override
            public Result execute(Request request) {
                return new Result("hidden", "hidden-1");
            }
        };
        PlatformAgentExecutor executor = new PlatformAgentExecutor(
                credentials, new ModelProviderRegistry(List.of(adapter)),
                new AgentOutputSchemaValidator(new ObjectMapper()),
                new AgentToolRegistry(List.of(tool, hidden)), new ObjectMapper(), 3);

        AgentExecutor.Result result = executor.execute(new AgentExecutor.Command(
                "tenant-a", version(), provider(), "hello", Duration.ofSeconds(10), "trace-1",
                null, "[\"lookup\"]"));

        assertThat(result.modelResponse().content()).isEqualTo("{\"answer\":\"done\"}");
        ArgumentCaptor<io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest> requests =
                ArgumentCaptor.forClass(io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest.class);
        verify(adapter, atLeast(3)).invoke(requests.capture());
        assertThat(requests.getAllValues().get(2).systemPrompt())
                .contains("lookup")
                .doesNotContain("hidden");
    }

    @Test
    void repairsFinalOutputOnceWhenProviderDoesNotFollowOutputContract() {
        AgentCredentialResolver credentials = mock(AgentCredentialResolver.class);
        ModelProviderPort adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        when(adapter.invoke(any())).thenReturn(
                response(route()),
                response("这不是 JSON"),
                response("{\"answer\":\"repaired\"}"));
        PlatformAgentExecutor executor = new PlatformAgentExecutor(
                credentials, new ModelProviderRegistry(List.of(adapter)),
                new AgentOutputSchemaValidator(new ObjectMapper()));

        AgentExecutor.Result result = executor.execute(new AgentExecutor.Command(
                "tenant-a", version(), provider(), "hello", Duration.ofSeconds(10), "trace-1"));

        assertThat(result.modelResponse().content()).isEqualTo("{\"answer\":\"repaired\"}");
    }

    @Test
    void resumesFromCheckpointWithoutCallingPlannerAgain() {
        AgentCredentialResolver credentials = mock(AgentCredentialResolver.class);
        ModelProviderPort adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        when(adapter.invoke(any())).thenReturn(response("{\"answer\":\"resumed\"}"));
        PlatformAgentExecutor executor = new PlatformAgentExecutor(
                credentials, new ModelProviderRegistry(List.of(adapter)),
                new AgentOutputSchemaValidator(new ObjectMapper()));

        AgentExecutor.Result result = executor.execute(new AgentExecutor.Command(
                "tenant-a", version(), provider(), "hello", Duration.ofSeconds(10), "trace-2", 42L,
                null, null,
                "{\"logicalStepId\":\"route:v1\",\"nextStep\":1,\"context\":\"已完成路由\",\"previousToolResultJson\":null}",
                null));

        assertThat(result.modelResponse().content()).isEqualTo("{\"answer\":\"resumed\"}");
        verify(adapter).invoke(any());
    }

    @Test
    void repairsInvalidRouteOnceAndPersistsStructuredRouteStep() {
        AgentCredentialResolver credentials = mock(AgentCredentialResolver.class);
        ModelProviderPort adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        when(adapter.invoke(any())).thenReturn(
                response("not-json"), response(route()), response("{\"answer\":\"done\"}"));
        PlatformAgentExecutor executor = new PlatformAgentExecutor(
                credentials, new ModelProviderRegistry(List.of(adapter)),
                new AgentOutputSchemaValidator(new ObjectMapper()));

        AgentExecutor.Result result = executor.execute(new AgentExecutor.Command(
                "tenant-a", version(), provider(), "hello", Duration.ofSeconds(10), "trace-route"));

        assertThat(result.steps()).first().satisfies(step -> {
            assertThat(step.stepType()).isEqualTo("ROUTE");
            assertThat(step.logicalStepId()).isEqualTo("route:v1");
        });
        verify(adapter, org.mockito.Mockito.times(3)).invoke(any());
    }

    @Test
    void abstainRouteStopsBeforeExecutionPhase() {
        AgentCredentialResolver credentials = mock(AgentCredentialResolver.class);
        ModelProviderPort adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        when(adapter.invoke(any())).thenReturn(response(
                "{\"schemaVersion\":\"1\",\"mode\":\"ABSTAIN\","
                        + "\"reason\":\"insufficient evidence\",\"requestedTools\":[],\"steps\":[]}"));
        PlatformAgentExecutor executor = new PlatformAgentExecutor(
                credentials, new ModelProviderRegistry(List.of(adapter)),
                new AgentOutputSchemaValidator(new ObjectMapper()));

        assertThatThrownBy(() -> executor.execute(new AgentExecutor.Command(
                "tenant-a", version(), provider(), "hello", Duration.ofSeconds(10), "trace-abstain")))
                .isInstanceOf(io.github.illuseahashmap.agent.runtime.application.AgentExecutionException.class)
                .extracting(exception -> ((io.github.illuseahashmap.agent.runtime.application.AgentExecutionException)
                        exception).failure().errorCode())
                .isEqualTo("AGENT_ROUTE_NOT_EXECUTABLE");
        verify(adapter).invoke(any());
    }

    @Test
    void refusesRevokedEarlierToolEvidenceBeforeAnyRecoveredModelRequest() {
        var adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        AgentTool tool = new AgentTool() {
            public String name() { return "lookup"; }
            public Result execute(Request request) { throw new AssertionError("No repeat external call"); }
            public void validateResultAccess(ResultAccessContext context, String output) {
                throw new IllegalStateException("earlier knowledge access revoked");
            }
        };
        var audits = mock(io.github.illuseahashmap.agent.runtime.application.port.AgentToolExecutionAuditRepository.class);
        when(audits.findSucceededByRun("tenant-a", 42, java.util.Set.of())).thenReturn(List.of(
                new io.github.illuseahashmap.agent.runtime.application.port.AgentToolExecutionAuditRepository.Audit(
                        "tenant-a", "lookup", "42:tool:1:lookup", "hash", "SUCCEEDED", "secret", null,
                        "old-trace", java.time.Instant.EPOCH)));
        var registry = new AgentToolRegistry(List.of(tool),
                io.github.illuseahashmap.agent.runtime.application.port.AgentToolPolicyRepository.ALLOW_ALL,
                audits, new AgentOutputSchemaValidator(new ObjectMapper()), new ObjectMapper());
        var executor = new PlatformAgentExecutor(mock(AgentCredentialResolver.class),
                new ModelProviderRegistry(List.of(adapter)), new AgentOutputSchemaValidator(new ObjectMapper()),
                registry, new ObjectMapper(), 4);
        assertThatThrownBy(() -> executor.execute(new AgentExecutor.Command("tenant-a", version(), provider(), "hello",
                Duration.ofSeconds(10), "trace", 42, null, null,
                "{\"schemaVersion\":\"3\",\"logicalStepId\":\"tool:2\",\"nextStep\":3,\"context\":\"older secret\",\"routeTools\":[\"lookup\"]}",
                null, "reader"))).hasMessageContaining("revoked");
        verify(adapter, org.mockito.Mockito.never()).invoke(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "3"})
    void refusesUnverifiableToolContextEvenWhenNoToolsRemainAvailable(String schemaVersion) {
        var adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        var executor = new PlatformAgentExecutor(mock(AgentCredentialResolver.class),
                new ModelProviderRegistry(List.of(adapter)), new AgentOutputSchemaValidator(new ObjectMapper()));
        String checkpoint = "{\"schemaVersion\":\"" + schemaVersion
                + "\",\"logicalStepId\":\"tool:1\",\"nextStep\":2,\"context\":\"unverified secret\",\"routeTools\":[]}";
        assertThatThrownBy(() -> executor.execute(new AgentExecutor.Command("tenant-a", version(), provider(), "hello",
                Duration.ofSeconds(10), "trace", 42, null, null, checkpoint, null, "reader")))
                .hasMessageContaining("no verifiable result ledger");
        verify(adapter, org.mockito.Mockito.never()).invoke(any());
    }

    private AgentDefinitionVersion version() {
        return new AgentDefinitionVersion(1L, "tenant-a", 1L, 1, AgentVersionStatus.PUBLISHED,
                AgentExecutionMode.PLATFORM_AGENT, 1L, "model", "system", 60,
                AgentFailurePolicy.FAIL_PROCESS, null, "{\"type\":\"object\"}",
                "user", "user", OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(),
                "[\"lookup\",\"context\"]");
    }

    @Test
    void deductsAuthorizationTimeBeforeEachModelRequest() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var registry = mock(AgentToolRegistry.class);
        when(registry.validateRunResultAccess(any())).thenAnswer(invocation -> {
            clock.addAndGet(Duration.ofMillis(200).toNanos());
            return 0;
        });
        var adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        when(adapter.invoke(any())).thenReturn(response(route()), response("{\"answer\":\"done\"}"));
        budgetExecutor(adapter, registry, clock).execute(new AgentExecutor.Command("tenant-a", version(), provider(), "hello",
                Duration.ofSeconds(1), "trace", 42, null, null, null, null, "reader"));
        var requests = ArgumentCaptor.forClass(io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest.class);
        verify(adapter, org.mockito.Mockito.times(2)).invoke(requests.capture());
        assertThat(requests.getAllValues()).extracting(request -> request.timeout())
                .containsExactly(Duration.ofMillis(800), Duration.ofMillis(600));
        assertThat(io.github.illuseahashmap.workflow.shared.context.ExecutionDeadline.remainingOrNull()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fresh", "route-repair", "tool-repair", "output-repair", "checkpoint"})
    void refusesExpiredAuthorizationBeforeInitialRepairedOrRecoveredModelRequest(String phase) {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        int expiresOn = switch (phase) {
            case "fresh" -> 1;
            case "route-repair", "checkpoint" -> 2;
            default -> 3;
        };
        var registry = mock(AgentToolRegistry.class);
        when(registry.validateRunResultAccess(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == expiresOn) { clock.set(Duration.ofSeconds(2).toNanos()); }
            return 0;
        });
        var adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        when(adapter.invoke(any())).thenReturn(response("route-repair".equals(phase) ? "invalid" : route()),
                response("tool-repair".equals(phase) ? "{\"action\":\"TOOL_CALL\"}" : "not-json"));
        String checkpoint = "checkpoint".equals(phase)
                ? "{\"schemaVersion\":\"3\",\"logicalStepId\":\"route:v1\",\"nextStep\":1,\"context\":\"route\",\"routeTools\":[]}" : null;
        assertThatThrownBy(() -> budgetExecutor(adapter, registry, clock).execute(new AgentExecutor.Command("tenant-a",
                version(), provider(), "hello", Duration.ofSeconds(1), "trace", 42, null, null, checkpoint, null, "reader")))
                .isInstanceOfSatisfying(io.github.illuseahashmap.agent.runtime.application.AgentExecutionException.class,
                        failure -> assertThat(failure.failure().errorCode()).isEqualTo("AGENT_DEADLINE_EXCEEDED"));
        verify(adapter, org.mockito.Mockito.times("checkpoint".equals(phase) ? 0 : expiresOn - 1)).invoke(any());
        assertThat(io.github.illuseahashmap.workflow.shared.context.ExecutionDeadline.remainingOrNull()).isNull();
    }

    @Test
    void rejectsModelResponseArrivingAfterTheSharedDeadline() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        when(adapter.invoke(any())).thenAnswer(invocation -> {
            clock.set(Duration.ofSeconds(2).toNanos());
            return response(route());
        });
        assertThatThrownBy(() -> budgetExecutor(adapter, mock(AgentToolRegistry.class), clock).execute(
                new AgentExecutor.Command("tenant-a", version(), provider(), "hello", Duration.ofSeconds(1), "trace")))
                .isInstanceOfSatisfying(io.github.illuseahashmap.agent.runtime.application.AgentExecutionException.class,
                        failure -> assertThat(failure.failure().errorCode()).isEqualTo("AGENT_DEADLINE_EXCEEDED"));
        verify(adapter).invoke(any());
    }

    private PlatformAgentExecutor budgetExecutor(ModelProviderPort adapter, AgentToolRegistry registry,
            java.util.concurrent.atomic.AtomicLong clock) {
        var mapper = new ObjectMapper();
        return new PlatformAgentExecutor(mock(AgentCredentialResolver.class), new ModelProviderRegistry(List.of(adapter)),
                new AgentOutputSchemaValidator(mapper), registry, mapper,
                new io.github.illuseahashmap.agent.runtime.application.routing.AgentRouteDecisionParser(mapper),
                new io.github.illuseahashmap.agent.runtime.application.checkpoint.CheckpointContextPolicy(40_000), 4, clock::get);
    }

    @Test
    void credentialLoadingConsumesTheSameBudgetRatherThanStartingANewModelWindow() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var credentials = mock(AgentCredentialResolver.class);
        when(credentials.resolve("tenant-a", 1L)).thenAnswer(invocation -> {
            clock.set(Duration.ofSeconds(2).toNanos());
            return "test-only";
        });
        var adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.OPENAI_COMPATIBLE);
        var mapper = new ObjectMapper();
        var executor = new PlatformAgentExecutor(credentials, new ModelProviderRegistry(List.of(adapter)),
                new AgentOutputSchemaValidator(mapper), mock(AgentToolRegistry.class), mapper,
                new io.github.illuseahashmap.agent.runtime.application.routing.AgentRouteDecisionParser(mapper),
                new io.github.illuseahashmap.agent.runtime.application.checkpoint.CheckpointContextPolicy(40_000), 4, clock::get);
        var provider = new AgentProvider(1L, "tenant-a", "test", "Test", AgentProviderType.OPENAI_COMPATIBLE,
                "https://example.test", "model", true, true, null, OffsetDateTime.now(), OffsetDateTime.now());
        assertThatThrownBy(() -> executor.execute(new AgentExecutor.Command("tenant-a", version(), provider, "hello",
                Duration.ofSeconds(1), "trace")))
                .isInstanceOfSatisfying(io.github.illuseahashmap.agent.runtime.application.AgentExecutionException.class,
                        failure -> assertThat(failure.failure().errorCode()).isEqualTo("AGENT_DEADLINE_EXCEEDED"));
        verify(adapter, org.mockito.Mockito.never()).invoke(any());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "content_filter,filtered,AGENT_RESULT_CONTENT_FILTERED",
            "length,partial,AGENT_RESULT_INCOMPLETE",
            "evidence_insufficient,invalid,AGENT_RESULT_EVIDENCE_INSUFFICIENT",
            "business_rejected,invalid,AGENT_RESULT_BUSINESS_REJECTED",
            "stop,{\"resultStatus\":\"REJECTED\"},AGENT_RESULT_BUSINESS_REJECTED"
    })
    void terminalSignalsNeverEnterRepair(String reason, String body, String code) {
        var model = mock(ModelProviderPort.class);
        when(model.providerType()).thenReturn(AgentProviderType.MOCK);
        when(model.invoke(any())).thenReturn(response(route()),
                new ModelProviderResponse(body,"model","blocked",reason,1,1,0,1), response("{\"accepted\":true}"));
        var executor = new PlatformAgentExecutor(mock(AgentCredentialResolver.class),
                new ModelProviderRegistry(List.of(model)), new AgentOutputSchemaValidator(new ObjectMapper()));
        assertThatThrownBy(() -> executor.execute(new AgentExecutor.Command("tenant-a", version(), provider(), "{}",
                Duration.ofSeconds(10), "trace"))).hasMessageContaining(code);
        verify(model, org.mockito.Mockito.times(2)).invoke(any());
    }

    private AgentProvider provider() {
        return new AgentProvider(1L, "tenant-a", "mock", "Mock", AgentProviderType.MOCK,
                "http://localhost", "model", true, false, null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private ModelProviderResponse response(String content) {
        return new ModelProviderResponse(content, "model", "request", "stop", 1, 1, 0, 1);
    }

    private String route(String... tools) {
        String requestedTools = java.util.Arrays.stream(tools)
                .map(tool -> "\"" + tool + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"schemaVersion\":\"1\",\"mode\":\"DIRECT\",\"reason\":\"bounded route\","
                + "\"requestedTools\":[" + requestedTools + "],\"steps\":[\"execute\"]}";
    }
}

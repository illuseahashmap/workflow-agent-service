package io.github.illuseahashmap.agent.runtime.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.agent.runtime.application.port.AgentToolExecutionAuditRepository;
import io.github.illuseahashmap.agent.runtime.application.port.AgentToolPolicyRepository;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentToolRegistryTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {200, 2000})
    void claimLatencyReducesToolBudgetAndExpiredClaimsNeverStartTheExternalTool(long claimMillis) {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var invoked = new java.util.concurrent.atomic.AtomicReference<Duration>();
        AgentTool tool = new AgentTool() {
            public String name() { return "lookup"; }
            public Result execute(Request request) {
                invoked.set(request.timeout());
                return new Result("{}", request.idempotencyKey());
            }
        };
        var audits = org.mockito.Mockito.mock(AgentToolExecutionAuditRepository.class);
        org.mockito.Mockito.when(audits.tryClaim(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            clock.set(Duration.ofMillis(claimMillis).toNanos());
            return new AgentToolExecutionAuditRepository.Claim(true, invocation.getArgument(0));
        });
        var mapper = new ObjectMapper();
        var registry = new AgentToolRegistry(List.of(tool), AgentToolPolicyRepository.ALLOW_ALL, audits,
                new AgentOutputSchemaValidator(mapper), mapper);
        try (var budget = io.github.illuseahashmap.workflow.shared.context.ExecutionDeadline.open(Duration.ofSeconds(1), clock::get)) {
            var request = new AgentTool.Request("tenant-a", Map.of(), Duration.ofSeconds(1), "trace", "key");
            if (claimMillis > 1000) {
                assertThatThrownBy(() -> registry.execute("tenant-a", "lookup", request))
                        .isInstanceOf(io.github.illuseahashmap.workflow.shared.context.ExecutionDeadline.Exceeded.class);
                assertThat(invoked.get()).isNull();
            } else {
                registry.execute("tenant-a", "lookup", request);
                assertThat(invoked.get()).isEqualTo(Duration.ofMillis(800));
            }
        }
    }


    @Test
    void frozenMetadataSurvivesCatalogChangesButRevocationStillHidesTool() {
        AgentTool tool = new AgentTool() {
            public String name() { return "lookup"; }
            public Result execute(Request request) { return new Result("{}", request.idempotencyKey()); }
        };
        var original = new io.github.illuseahashmap.agent.runtime.application.port.AgentToolDefinition(
                "lookup", "Original description", "{\"type\":\"object\"}", true);
        var current = new java.util.concurrent.atomic.AtomicReference<>(original);
        AgentToolPolicyRepository policy = (tenant, code) -> Optional.ofNullable(current.get());
        var snapshots = org.mockito.Mockito.mock(
                io.github.illuseahashmap.agent.runtime.application.port.AgentToolSnapshotRepository.class);
        org.mockito.Mockito.when(snapshots.find("tenant-a", 1L)).thenReturn(List.of(original));
        var mapper = new ObjectMapper();
        var registry = new AgentToolRegistry(List.of(tool), policy, AgentToolExecutionAuditRepository.NOOP,
                new AgentOutputSchemaValidator(mapper), mapper, List.of(), snapshots);
        current.set(new io.github.illuseahashmap.agent.runtime.application.port.AgentToolDefinition(
                "lookup", "Changed description", "{\"type\":\"object\",\"required\":[\"newField\"]}", true));
        assertThat(registry.availableToolDefinitions("tenant-a", 1L, "[\"lookup\"]", null))
                .extracting(ModelProviderRequest.ToolDefinition::description).containsExactly("Original description");
        current.set(null);
        assertThat(registry.availableToolDefinitions("tenant-a", 1L, "[\"lookup\"]", null)).isEmpty();
    }

    @Test
    void refusesMissingSnapshotForAuthorizedRegisteredTool() {
        AgentTool tool = new AgentTool() {
            public String name() { return "lookup"; }
            public Result execute(Request request) { throw new AssertionError("must not execute"); }
        };
        var snapshots = org.mockito.Mockito.mock(
                io.github.illuseahashmap.agent.runtime.application.port.AgentToolSnapshotRepository.class);
        org.mockito.Mockito.when(snapshots.find("tenant-a", 1L)).thenReturn(List.of());
        var mapper = new ObjectMapper();
        var registry = new AgentToolRegistry(List.of(tool), AgentToolPolicyRepository.ALLOW_ALL,
                AgentToolExecutionAuditRepository.NOOP, new AgentOutputSchemaValidator(mapper), mapper, List.of(), snapshots);
        assertThatThrownBy(() -> registry.availableToolDefinitions("tenant-a", 1L, "[\"lookup\"]", null))
                .isInstanceOf(AgentExecutionException.class).hasMessageContaining("incomplete");
    }

    @Test
    void reusesAuditedResultForTheSameIdempotencyKey() {
        var audits = new InMemoryAuditRepository();
        var calls = new int[] {0};
        AgentTool tool = new AgentTool() {
            @Override
            public String name() {
                return "lookup";
            }

            @Override
            public Result execute(Request request) {
                calls[0]++;
                return new Result("{\"value\":1}", request.idempotencyKey());
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(tool), AgentToolPolicyRepository.ALLOW_ALL, audits,
                new io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator(
                        new ObjectMapper()), new ObjectMapper());
        var request = new AgentTool.Request("tenant-a", Map.of("id", 1),
                Duration.ofSeconds(1), "trace-1", "key-1");

        registry.execute("tenant-a", "lookup", request);
        AgentTool.Result second = registry.execute("tenant-a", "lookup", request);

        assertThat(second.output()).isEqualTo("{\"value\":1}");
        assertThat(calls[0]).isEqualTo(1);
    }

    @Test
    void rejectsIdempotencyKeyReuseWithDifferentArguments() {
        var audits = new InMemoryAuditRepository();
        AgentTool tool = new AgentTool() {
            @Override
            public String name() {
                return "lookup";
            }

            @Override
            public Result execute(Request request) {
                return new Result("{}", request.idempotencyKey());
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(tool), AgentToolPolicyRepository.ALLOW_ALL, audits,
                new io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator(
                        new ObjectMapper()), new ObjectMapper());
        var first = new AgentTool.Request("tenant-a", Map.of("id", 1),
                Duration.ofSeconds(1), "trace-1", "key-1");
        registry.execute("tenant-a", "lookup", first);

        var second = new AgentTool.Request("tenant-a", Map.of("id", 2),
                Duration.ofSeconds(1), "trace-1", "key-1");
        assertThatThrownBy(() -> registry.execute("tenant-a", "lookup", second))
                .hasMessageContaining("different arguments");
    }

    @Test
    void restoresOnlyCommittedToolOutputForCheckpointRecovery() {
        var audits = new InMemoryAuditRepository();
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "lookup"; }
            @Override public Result execute(Request request) {
                return new Result("{\"value\":1}", request.idempotencyKey());
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(tool), AgentToolPolicyRepository.ALLOW_ALL, audits,
                new AgentOutputSchemaValidator(new ObjectMapper()), new ObjectMapper());
        AgentTool.Result result = registry.execute("tenant-a", "lookup", new AgentTool.Request(
                "tenant-a", Map.of("id", 1), Duration.ofSeconds(1), "trace-1", null, null, 42, "tool:1"));
        var access = new AgentTool.ResultAccessContext("tenant-a", null, null, null, null);
        var ledger = new AgentToolRegistry.RunHistory(access, 42);
        registry.validateRunResultAccess(ledger);
        assertThat(registry.restoreStep(ledger, 1, "lookup", result.idempotencyKey(), "{\"id\":1}"))
                .isEqualTo("{\"value\":1}");
        assertThatThrownBy(() -> registry.restoreStep(new AgentToolRegistry.RunHistory(access, 43), 1, "lookup", result.idempotencyKey(), "{\"id\":1}"))
                .hasMessageContaining("does not belong");
        assertThatThrownBy(() -> registry.restoreStep(ledger, 1, "lookup", result.idempotencyKey(), "{\"id\":2}"))
                .hasMessageContaining("does not belong");
    }

    @Test
    void rechecksDataPermissionsForIdempotencyAndCheckpointReplay() {
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        AgentTool tool = new AgentTool() {
            public String name() { return "knowledge"; }
            public Result execute(Request request) {
                calls.incrementAndGet();
                return new Result("secret", request.idempotencyKey());
            }
            public void validateResultAccess(ResultAccessContext context, String output) {
                if (!allowed.get()) { throw new IllegalStateException("revoked"); }
            }
        };
        var registry = new AgentToolRegistry(List.of(tool), AgentToolPolicyRepository.ALLOW_ALL,
                new InMemoryAuditRepository(), new AgentOutputSchemaValidator(new ObjectMapper()), new ObjectMapper());
        var request = new AgentTool.Request("tenant-a", Map.of(), Duration.ofSeconds(1), "trace", null, null, 42, "tool:1");
        registry.execute("tenant-a", "knowledge", request);
        allowed.set(false);
        assertThatThrownBy(() -> registry.execute("tenant-a", "knowledge", request)).hasMessageContaining("revoked");
        assertThatThrownBy(() -> registry.validateRunResultAccess(
                new AgentToolRegistry.RunHistory(new AgentTool.ResultAccessContext("tenant-a", 1L, "user", null, null), 42)))
                .hasMessageContaining("revoked");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void exposesOnlyTheVersionAndNodeToolIntersection() {
        AgentTool lookup = new AgentTool() {
            @Override public String name() { return "lookup"; }
            @Override public Result execute(Request request) { return new Result("{}", request.idempotencyKey()); }
        };
        AgentTool other = new AgentTool() {
            @Override public String name() { return "other"; }
            @Override public Result execute(Request request) { return new Result("{}", request.idempotencyKey()); }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(lookup, other));

        assertThat(registry.availableToolDefinitions("tenant-a", "[\"lookup\",\"other\"]",
                "[\"lookup\"]")).extracting(ModelProviderRequest.ToolDefinition::name)
                .containsExactly("lookup");
    }

    @Test
    void reusesStableRunStepKeyAcrossAttemptsAndTraces() {
        var audits = new InMemoryAuditRepository();
        var calls = new int[] {0};
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "lookup"; }
            @Override public Result execute(Request request) {
                calls[0]++;
                return new Result("{\"value\":1}", "ignored");
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(tool), AgentToolPolicyRepository.ALLOW_ALL, audits,
                new AgentOutputSchemaValidator(new ObjectMapper()), new ObjectMapper());
        registry.execute("tenant-a", "lookup", new AgentTool.Request(
                "tenant-a", Map.of("id", 1), Duration.ofSeconds(1), "trace-1",
                null, null, 42L, "tool:1"));
        AgentTool.Result replay = registry.execute("tenant-a", "lookup", new AgentTool.Request(
                "tenant-a", Map.of("id", 1), Duration.ofSeconds(1), "trace-2",
                null, null, 42L, "tool:1"));

        assertThat(replay.output()).isEqualTo("{\"value\":1}");
        assertThat(calls[0]).isEqualTo(1);
    }

    @Test
    void rejectsDifferentArgumentsForTheSameStableRunStepKey() {
        var audits = new InMemoryAuditRepository();
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "lookup"; }
            @Override public Result execute(Request request) { return new Result("{}", request.idempotencyKey()); }
        };
        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(tool), AgentToolPolicyRepository.ALLOW_ALL, audits,
                new AgentOutputSchemaValidator(new ObjectMapper()), new ObjectMapper());
        registry.execute("tenant-a", "lookup", new AgentTool.Request(
                "tenant-a", Map.of("id", 1), Duration.ofSeconds(1), "trace-1",
                null, null, 42L, "tool:1"));

        assertThatThrownBy(() -> registry.execute("tenant-a", "lookup", new AgentTool.Request(
                "tenant-a", Map.of("id", 2), Duration.ofSeconds(1), "trace-2",
                null, null, 42L, "tool:1")))
                .hasMessageContaining("different arguments");
    }

    @Test
    void rejectsDifferentArgumentsWhenAStableStepIsRetriedAfterFailure() {
        var audits = new InMemoryAuditRepository();
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "lookup"; }
            @Override public Result execute(Request request) {
                throw new IllegalStateException("temporary tool failure");
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(tool), AgentToolPolicyRepository.ALLOW_ALL, audits,
                new AgentOutputSchemaValidator(new ObjectMapper()), new ObjectMapper());
        var first = new AgentTool.Request("tenant-a", Map.of("id", 1),
                Duration.ofSeconds(1), "trace-1", null, null, 42L, "tool:1");

        assertThatThrownBy(() -> registry.execute("tenant-a", "lookup", first))
                .hasMessageContaining("temporary tool failure");

        var retryWithDifferentArguments = new AgentTool.Request(
                "tenant-a", Map.of("id", 2), Duration.ofSeconds(1), "trace-2",
                null, null, 42L, "tool:1");
        assertThatThrownBy(() -> registry.execute("tenant-a", "lookup", retryWithDifferentArguments))
                .hasMessageContaining("different arguments");
    }

    private static final class InMemoryAuditRepository implements AgentToolExecutionAuditRepository {
        private final Map<String, Audit> entries = new HashMap<>();

        @Override
        public List<Audit> findSucceededByRun(String tenantCode, long runId, java.util.Set<String> loadedKeys) {
            return entries.values().stream().filter(audit -> audit.tenantCode().equals(tenantCode)
                    && audit.idempotencyKey().startsWith(runId + ":") && audit.status().equals("SUCCEEDED")).toList();
        }

        @Override
        public Optional<Audit> findByIdempotencyKey(String tenantCode, String toolCode, String idempotencyKey) {
            return Optional.ofNullable(entries.get(tenantCode + ":" + toolCode + ":" + idempotencyKey));
        }

        @Override
        public void save(Audit audit) {
            entries.put(audit.tenantCode() + ":" + audit.toolCode() + ":" + audit.idempotencyKey(), audit);
        }
    }
}

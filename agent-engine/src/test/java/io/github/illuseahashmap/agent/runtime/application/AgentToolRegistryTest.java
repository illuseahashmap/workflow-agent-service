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
        public Optional<Audit> findByIdempotencyKey(String tenantCode, String toolCode, String idempotencyKey) {
            return Optional.ofNullable(entries.get(tenantCode + ":" + toolCode + ":" + idempotencyKey));
        }

        @Override
        public void save(Audit audit) {
            entries.put(audit.tenantCode() + ":" + audit.toolCode() + ":" + audit.idempotencyKey(), audit);
        }
    }
}

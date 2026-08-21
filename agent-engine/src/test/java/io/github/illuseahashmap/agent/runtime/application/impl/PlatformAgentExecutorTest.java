package io.github.illuseahashmap.agent.runtime.application.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

class PlatformAgentExecutorTest {

    @Test
    void runsBoundedPlanAndAnswerPhasesBeforeReturningFinalContent() {
        AgentCredentialResolver credentials = mock(AgentCredentialResolver.class);
        ModelProviderPort adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        when(adapter.invoke(any())).thenReturn(
                response("plan: inspect request"), response("{\"answer\":\"done\"}"));
        PlatformAgentExecutor executor = new PlatformAgentExecutor(
                credentials, new ModelProviderRegistry(List.of(adapter)),
                new AgentOutputSchemaValidator(new ObjectMapper()));

        AgentExecutor.Result result = executor.execute(new AgentExecutor.Command(
                "tenant-a", version(), provider(), "hello", Duration.ofSeconds(10), "trace-1"));

        assertThat(result.modelResponse().content()).isEqualTo("{\"answer\":\"done\"}");
    }

    @Test
    void executesOnlyARegisteredToolRequestedByTheModel() {
        AgentCredentialResolver credentials = mock(AgentCredentialResolver.class);
        ModelProviderPort adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        when(adapter.invoke(any())).thenReturn(
                response("plan"),
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
                .contains("PLAN", "TOOL_CALL", "VALIDATION");
    }

    @Test
    void treatsMissingToolArgumentsAsEmptyRuntimeOwnedArguments() {
        AgentCredentialResolver credentials = mock(AgentCredentialResolver.class);
        ModelProviderPort adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        when(adapter.invoke(any())).thenReturn(
                response("plan"),
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
                response("plan"),
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
        PlatformAgentExecutor executor = new PlatformAgentExecutor(
                credentials, new ModelProviderRegistry(List.of(adapter)),
                new AgentOutputSchemaValidator(new ObjectMapper()),
                new AgentToolRegistry(List.of(tool)), new ObjectMapper(), 3);

        AgentExecutor.Result result = executor.execute(new AgentExecutor.Command(
                "tenant-a", version(), provider(), "hello", Duration.ofSeconds(10), "trace-1"));

        assertThat(result.modelResponse().content()).isEqualTo("{\"answer\":\"done\"}");
    }

    @Test
    void repairsFinalOutputOnceWhenProviderDoesNotFollowOutputContract() {
        AgentCredentialResolver credentials = mock(AgentCredentialResolver.class);
        ModelProviderPort adapter = mock(ModelProviderPort.class);
        when(adapter.providerType()).thenReturn(AgentProviderType.MOCK);
        when(adapter.invoke(any())).thenReturn(
                response("plan"),
                response("这不是 JSON"),
                response("{\"answer\":\"repaired\"}"));
        PlatformAgentExecutor executor = new PlatformAgentExecutor(
                credentials, new ModelProviderRegistry(List.of(adapter)),
                new AgentOutputSchemaValidator(new ObjectMapper()));

        AgentExecutor.Result result = executor.execute(new AgentExecutor.Command(
                "tenant-a", version(), provider(), "hello", Duration.ofSeconds(10), "trace-1"));

        assertThat(result.modelResponse().content()).isEqualTo("{\"answer\":\"repaired\"}");
    }

    private AgentDefinitionVersion version() {
        return new AgentDefinitionVersion(1L, "tenant-a", 1L, 1, AgentVersionStatus.PUBLISHED,
                AgentExecutionMode.PLATFORM_AGENT, 1L, "model", "system", 60,
                AgentFailurePolicy.FAIL_PROCESS, null, "{\"type\":\"object\"}",
                "user", "user", OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private AgentProvider provider() {
        return new AgentProvider(1L, "tenant-a", "mock", "Mock", AgentProviderType.MOCK,
                "http://localhost", "model", true, false, null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private ModelProviderResponse response(String content) {
        return new ModelProviderResponse(content, "model", "request", "stop", 1, 1, 0, 1);
    }
}

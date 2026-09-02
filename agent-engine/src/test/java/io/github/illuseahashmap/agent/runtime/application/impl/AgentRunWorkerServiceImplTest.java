package io.github.illuseahashmap.agent.runtime.application.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersionRepository;
import io.github.illuseahashmap.agent.definition.domain.AgentFailurePolicy;
import io.github.illuseahashmap.agent.definition.domain.AgentVersionStatus;
import io.github.illuseahashmap.agent.provider.application.ModelProviderRegistry;
import io.github.illuseahashmap.agent.provider.application.port.AgentCredentialResolver;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderFailureKind;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderPort;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderRepository;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.agent.provider.infrastructure.mock.MockModelProviderAdapter;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunExecutionRepository;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunExecutionSnapshot;
import io.github.illuseahashmap.agent.runtime.application.port.AgentExecutor;
import io.github.illuseahashmap.agent.runtime.application.AgentExecutorRegistry;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunEventPublisher;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunLeaseHeartbeat;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRuntimeMetrics;
import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;
import io.github.illuseahashmap.agent.runtime.domain.AgentRun;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStatus;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class AgentRunWorkerServiceImplTest {

    private final AgentRunExecutionRepository executionRepository = mock(AgentRunExecutionRepository.class);
    private final AgentDefinitionVersionRepository versionRepository =
            mock(AgentDefinitionVersionRepository.class);
    private final AgentProviderRepository providerRepository = mock(AgentProviderRepository.class);
    private final AgentCredentialResolver credentialResolver = mock(AgentCredentialResolver.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    @BeforeEach
    void configureTransactionManager() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Test
    void executesQueuedRunThroughMockAdapterAndCompletesLedger() {
        AgentRun run = queuedRun();
        configureClaim(run);
        when(versionRepository.findByVersionId("tenant-a", 20L)).thenReturn(Optional.of(version(30L)));
        when(providerRepository.findById("tenant-a", 30L)).thenReturn(Optional.of(provider(
                AgentProviderType.MOCK)));
        AgentRunWorkerServiceImpl service = service(new MockModelProviderAdapter());

        boolean executed = service.executeNext();

        assertThat(executed).isTrue();
        assertThat(run.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        verify(executionRepository).saveClaimed(any(), any());
        verify(executionRepository).insertCheckpoint(
                org.mockito.ArgumentMatchers.eq("tenant-a"),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq("MODEL_RESULT"),
                org.mockito.ArgumentMatchers.contains("Mock result"),
                any());
        verify(executionRepository).saveSucceeded(
                any(),
                org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.eq(201L),
                org.mockito.ArgumentMatchers.eq(30L),
                org.mockito.ArgumentMatchers.eq("mock-model"),
                any(),
                org.mockito.ArgumentMatchers.contains("Mock result"),
                any());
    }

    @Test
    void requeuesRetryableProviderFailureWithoutMarkingRunFailed() {
        AgentRun run = queuedRun();
        configureClaim(run);
        when(versionRepository.findByVersionId("tenant-a", 20L)).thenReturn(Optional.of(version(30L)));
        when(providerRepository.findById("tenant-a", 30L)).thenReturn(Optional.of(provider(
                AgentProviderType.OPENAI_COMPATIBLE)));
        when(credentialResolver.resolve("tenant-a", 30L)).thenReturn("secret");
        ModelProviderPort providerPort = mock(ModelProviderPort.class);
        when(providerPort.providerType()).thenReturn(AgentProviderType.OPENAI_COMPATIBLE);
        when(providerPort.invoke(any())).thenThrow(new ModelProviderException(
                "PROVIDER_UNAVAILABLE",
                ModelProviderFailureKind.RETRYABLE,
                "unavailable"));
        AgentRunWorkerServiceImpl service = service(providerPort);

        service.executeNext();

        ArgumentCaptor<AgentRun> runCaptor = ArgumentCaptor.forClass(AgentRun.class);
        verify(executionRepository).saveFailed(
                runCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.eq(201L),
                org.mockito.ArgumentMatchers.eq(30L),
                org.mockito.ArgumentMatchers.eq("mock-model"),
                org.mockito.ArgumentMatchers.eq("PROVIDER_UNAVAILABLE"),
                org.mockito.ArgumentMatchers.eq(
                        io.github.illuseahashmap.agent.runtime.domain.ResultStatus.FAILED),
                any(),
                any());
        assertThat(runCaptor.getValue().status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(runCaptor.getValue().currentAttemptId()).isNull();
    }

    @Test
    void rejectsStructurallyValidButEmptyBusinessResult() {
        AgentRun run = queuedRun();
        configureClaim(run);
        when(versionRepository.findByVersionId("tenant-a", 20L)).thenReturn(Optional.of(version(30L)));
        when(providerRepository.findById("tenant-a", 30L)).thenReturn(Optional.of(provider(AgentProviderType.MOCK)));
        ModelProviderPort providerPort = mock(ModelProviderPort.class);
        when(providerPort.providerType()).thenReturn(AgentProviderType.MOCK);
        when(providerPort.invoke(any())).thenReturn(new ModelProviderResponse(
                " ", "mock-model", "request-1", "stop", 1, 0, 0, 5));

        service(providerPort).executeNext();

        verify(executionRepository).saveFailed(
                any(),
                org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.eq(201L),
                org.mockito.ArgumentMatchers.eq(30L),
                org.mockito.ArgumentMatchers.eq("mock-model"),
                org.mockito.ArgumentMatchers.eq("AGENT_RESULT_EMPTY"),
                org.mockito.ArgumentMatchers.eq(ResultStatus.EMPTY),
                any(),
                any());
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
    }

    @Test
    void workerTakeoverPassesTheDurableCheckpointToTheNextAttempt() {
        AgentRun run = queuedRun();
        configureClaim(run);
        when(executionRepository.findLatestCheckpoint("tenant-a", 10L)).thenReturn(Optional.of(
                new AgentRunExecutionRepository.CheckpointSnapshot(2, "STEP_COMPLETED",
                        "{\"logicalStepId\":\"mcp:1\",\"nextStep\":2,\"context\":\"tool result\"}")));
        AgentDefinitionVersion platformVersion = version(30L, AgentExecutionMode.PLATFORM_AGENT);
        when(versionRepository.findByVersionId("tenant-a", 20L)).thenReturn(Optional.of(platformVersion));
        when(providerRepository.findById("tenant-a", 30L)).thenReturn(Optional.of(provider(AgentProviderType.MOCK)));
        AgentExecutor executor = mock(AgentExecutor.class);
        when(executor.executionMode()).thenReturn(AgentExecutionMode.PLATFORM_AGENT);
        when(executor.execute(any())).thenReturn(new AgentExecutor.Result(30L, "mock-model",
                new ModelProviderResponse("{\"answer\":\"recovered\"}", "mock-model", "request", "stop",
                        1, 1, 0, 1)));
        AgentRunWorkerServiceImpl service = new AgentRunWorkerServiceImpl(
                executionRepository, versionRepository, providerRepository,
                new AgentExecutorRegistry(List.of(executor)), new ObjectMapper(),
                new TransactionTemplate(transactionManager), "test-worker", 30, 3, 2,
                AgentRunEventPublisher.NOOP, AgentRunLeaseHeartbeat.NOOP,
                new DefaultAgentResultPolicy(), AgentRuntimeMetrics.NOOP,
                ExponentialJitterRetryBackoffPolicy.defaults(java.util.random.RandomGenerator.getDefault()),
                new AgentRecoveryPolicy(), new AgentFailureMapper());

        assertThat(service.executeNext()).isTrue();

        ArgumentCaptor<AgentExecutor.Command> command = ArgumentCaptor.forClass(AgentExecutor.Command.class);
        verify(executor).execute(command.capture());
        assertThat(command.getValue().checkpointStateJson()).contains("tool result");
        assertThat(command.getValue().runId()).isEqualTo(10L);
    }

    private AgentRunWorkerServiceImpl service(ModelProviderPort providerPort) {
        return new AgentRunWorkerServiceImpl(
                executionRepository,
                versionRepository,
                providerRepository,
                credentialResolver,
                new ModelProviderRegistry(List.of(providerPort)),
                new ObjectMapper(),
                new TransactionTemplate(transactionManager),
                "test-worker",
                30,
                3);
    }

    private void configureClaim(AgentRun run) {
        when(executionRepository.lockNextAvailable(any(), any(Integer.class))).thenReturn(Optional.of(
                new AgentRunExecutionSnapshot(run, "{\"input\":\"review this\"}")));
        when(executionRepository.nextAttemptNumber("tenant-a", 10L)).thenReturn(1);
        when(executionRepository.insertRunningAttempt(
                org.mockito.ArgumentMatchers.eq("tenant-a"),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(1),
                any())).thenReturn(101L);
        when(executionRepository.insertRunningStep(
                org.mockito.ArgumentMatchers.eq("tenant-a"),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(101L),
                any())).thenReturn(201L);
    }

    private AgentRun queuedRun() {
        Instant now = Instant.now();
        return AgentRun.queued(10L, "tenant-a", 20L, "idempotency-1", now.plusSeconds(120), now);
    }

    private AgentDefinitionVersion version(long providerId) {
        return version(providerId, AgentExecutionMode.MODEL_ONLY);
    }

    private AgentDefinitionVersion version(long providerId, AgentExecutionMode executionMode) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentDefinitionVersion(
                20L,
                "tenant-a",
                1L,
                1,
                AgentVersionStatus.PUBLISHED,
                executionMode,
                providerId,
                "mock-model",
                "system prompt",
                120,
                AgentFailurePolicy.FAIL_PROCESS,
                null,
                "{}",
                "admin",
                "admin",
                now,
                now,
                now);
    }

    private AgentProvider provider(AgentProviderType type) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentProvider(
                30L,
                "tenant-a",
                "provider",
                "Provider",
                type,
                "http://localhost/v1",
                "mock-model",
                true,
                type == AgentProviderType.OPENAI_COMPATIBLE,
                null,
                now,
                now);
    }
}

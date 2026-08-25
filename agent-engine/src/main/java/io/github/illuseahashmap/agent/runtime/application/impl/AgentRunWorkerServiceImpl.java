package io.github.illuseahashmap.agent.runtime.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersionRepository;
import io.github.illuseahashmap.agent.provider.application.ModelProviderRegistry;
import io.github.illuseahashmap.agent.provider.application.port.AgentCredentialResolver;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderRepository;
import io.github.illuseahashmap.agent.runtime.application.AgentExecutorRegistry;
import io.github.illuseahashmap.agent.runtime.application.AgentExecutionException;
import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.agent.runtime.application.AgentRunWorkerService;
import io.github.illuseahashmap.agent.runtime.application.port.AgentExecutor;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunExecutionRepository;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunExecutionSnapshot;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRecoveryDecision;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunEventPublisher;
import io.github.illuseahashmap.agent.runtime.application.port.AgentResultPolicy;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRetryBackoffPolicy;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunLeaseHeartbeat;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRuntimeMetrics;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailureDisposition;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailure;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory;
import io.github.illuseahashmap.agent.runtime.domain.AgentRun;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunLease;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunOperatorType;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStateMachine;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStateTransition;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunTransitionContext;
import io.github.illuseahashmap.agent.runtime.domain.AgentTimeoutType;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class AgentRunWorkerServiceImpl implements AgentRunWorkerService {

    private static final Logger LOG = LoggerFactory.getLogger(AgentRunWorkerServiceImpl.class);

    private final AgentRunExecutionRepository executionRepository;
    private final AgentDefinitionVersionRepository versionRepository;
    private final AgentProviderRepository providerRepository;
    private final AgentExecutorRegistry executorRegistry;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final AgentRunStateMachine stateMachine = new AgentRunStateMachine();
    private final String workerId;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final int tenantConcurrencyLimit;
    private final AgentRunEventPublisher eventPublisher;
    private final AgentRunLeaseHeartbeat leaseHeartbeat;
    private final AgentResultPolicy resultPolicy;
    private final AgentRuntimeMetrics metrics;
    private final AgentRetryBackoffPolicy retryBackoffPolicy;
    private final AgentRecoveryPolicy recoveryPolicy;
    private final AgentFailureMapper failureMapper;

    @Autowired
    public AgentRunWorkerServiceImpl(
            AgentRunExecutionRepository executionRepository,
            AgentDefinitionVersionRepository versionRepository,
            AgentProviderRepository providerRepository,
            AgentExecutorRegistry executorRegistry,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            @Value("${workflow.agent.worker.id:local-worker}") String workerId,
            @Value("${workflow.agent.worker.lease-seconds:60}") long leaseSeconds,
            @Value("${workflow.agent.worker.max-attempts:3}") int maxAttempts,
            @Value("${workflow.agent.worker.max-running-per-tenant:2}") int tenantConcurrencyLimit,
            AgentRunEventPublisher eventPublisher,
            AgentRunLeaseHeartbeat leaseHeartbeat,
            AgentResultPolicy resultPolicy,
            AgentRuntimeMetrics metrics,
            AgentRetryBackoffPolicy retryBackoffPolicy,
            AgentRecoveryPolicy recoveryPolicy,
            AgentFailureMapper failureMapper
    ) {
        this.executionRepository = executionRepository;
        this.versionRepository = versionRepository;
        this.providerRepository = providerRepository;
        this.executorRegistry = executorRegistry;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.workerId = workerId + ":" + UUID.randomUUID();
        this.leaseDuration = Duration.ofSeconds(Math.max(5L, Math.min(leaseSeconds, 3_600L)));
        this.maxAttempts = maxAttempts;
        this.tenantConcurrencyLimit = Math.max(1, Math.min(tenantConcurrencyLimit, 10_000));
        this.eventPublisher = eventPublisher;
        this.leaseHeartbeat = leaseHeartbeat;
        this.resultPolicy = resultPolicy;
        this.metrics = metrics;
        this.retryBackoffPolicy = retryBackoffPolicy;
        this.recoveryPolicy = recoveryPolicy;
        this.failureMapper = failureMapper;
    }

    public AgentRunWorkerServiceImpl(
            AgentRunExecutionRepository executionRepository,
            AgentDefinitionVersionRepository versionRepository,
            AgentProviderRepository providerRepository,
            AgentCredentialResolver credentialResolver,
            ModelProviderRegistry providerRegistry,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            String workerId,
            long leaseSeconds,
            int maxAttempts
    ) {
        this(executionRepository, versionRepository, providerRepository,
                new AgentExecutorRegistry(List.of(new ModelOnlyAgentExecutor(
                        credentialResolver, providerRegistry, new AgentOutputSchemaValidator(objectMapper)))),
                objectMapper, transactionTemplate, workerId, leaseSeconds, maxAttempts,
                2,
                AgentRunEventPublisher.NOOP,
                AgentRunLeaseHeartbeat.NOOP,
                new DefaultAgentResultPolicy(),
                AgentRuntimeMetrics.NOOP,
                ExponentialJitterRetryBackoffPolicy.defaults(java.util.random.RandomGenerator.getDefault()),
                new AgentRecoveryPolicy(), new AgentFailureMapper());
    }

    @Override
    public boolean executeNext() {
        return TrustedDataAccessContext.runAsSystemWorker(() -> {
            ClaimedRun claimed = transactionTemplate.execute(status -> claim());
            if (claimed == null) {
                return false;
            }
            execute(claimed);
            return true;
        });
    }

    @Override
    public int recoverExpiredRuns() {
        int recovered = TrustedDataAccessContext.runAsSystemWorker(() ->
                transactionTemplate.execute(status -> executionRepository.recoverExpired(Instant.now())));
        metrics.recovered(recovered);
        return recovered;
    }

    private ClaimedRun claim() {
        Instant now = Instant.now();
        AgentRunExecutionSnapshot snapshot = executionRepository
                .lockNextAvailable(now, tenantConcurrencyLimit).orElse(null);
        if (snapshot == null) {
            return null;
        }
        AgentRun run = snapshot.run();
        int attemptNumber = executionRepository.nextAttemptNumber(run.tenantCode(), run.id());
        long attemptId = executionRepository.insertRunningAttempt(
                run.tenantCode(), run.id(), attemptNumber, now);
        long stepId = executionRepository.insertRunningStep(run.tenantCode(), run.id(), attemptId, now);
        Instant leaseExpiresAt = now.plus(leaseDuration);
        if (leaseExpiresAt.isAfter(run.deadlineAt())) {
            leaseExpiresAt = run.deadlineAt();
        }
        String traceId = UUID.randomUUID().toString();
        stateMachine.startLease(
                run,
                new AgentRunLease(attemptId, workerId, leaseExpiresAt),
                transition(attemptId, "WORKER_CLAIMED", traceId, now));
        executionRepository.saveClaimed(run, lastTransition(run));
        metrics.claimed();
        return new ClaimedRun(
                run, attemptId, attemptNumber, stepId, traceId, snapshot.inputSnapshotJson());
    }

    private void execute(ClaimedRun claimed) {
        AgentRun run = claimed.run();
        AgentRunLeaseHeartbeat.LeaseCommand leaseCommand = new AgentRunLeaseHeartbeat.LeaseCommand(
                run.tenantCode(), run.id(), claimed.attemptId(), workerId, leaseDuration, run.deadlineAt());
        try (AgentRunLeaseHeartbeat.LeaseHandle lease = leaseHeartbeat.start(leaseCommand)) {
            AgentExecutor.Result executionResult;
            AgentDefinitionVersion version;
            try {
                version = versionRepository.findByVersionId(run.tenantCode(), run.agentVersionId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent version does not exist"));
                AgentProvider provider = providerRepository.findById(run.tenantCode(), version.providerId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent Provider does not exist"));
                String userInput = extractInput(claimed.inputSnapshotJson());
                String nodeToolSetJson = extractNodeToolSet(claimed.inputSnapshotJson());
                executionResult = executorRegistry.require(version.executionMode()).execute(
                        new AgentExecutor.Command(run.tenantCode(), version, provider, userInput,
                                remainingTimeout(run, version.timeoutSeconds()), claimed.traceId(),
                                run.processInstanceId(), nodeToolSetJson,
                                (sequence, step) -> persistProgress(claimed, sequence, step)));
            } catch (AgentExecutionException exception) {
                LOG.warn("Agent execution contract failed: runId={}, traceId={}, errorCode={}",
                        run.id(), claimed.traceId(), exception.failure().errorCode(), exception);
                completeIfLeaseValid(lease, () -> completeFailed(
                        claimed, providerId(run), requestedModel(run), exception.failure()));
                return;
            } catch (ModelProviderException exception) {
                LOG.warn("Agent provider execution failed: runId={}, traceId={}, errorCode={}",
                        run.id(), claimed.traceId(), exception.errorCode(), exception);
                completeIfLeaseValid(lease, () -> completeFailed(
                        claimed, providerId(run), requestedModel(run), failureMapper.fromProvider(exception)));
                return;
            } catch (BusinessException exception) {
                LOG.warn("Agent configuration failed: runId={}, traceId={}, message={}",
                        run.id(), claimed.traceId(), exception.getMessage(), exception);
                completeIfLeaseValid(lease, () -> completeFailed(
                        claimed, providerId(run), requestedModel(run), failureMapper.configuration(exception)));
                return;
            } catch (RuntimeException exception) {
                LOG.error("Agent execution failed unexpectedly: runId={}, traceId={}",
                        run.id(), claimed.traceId(), exception);
                completeIfLeaseValid(lease, () -> completeFailed(
                        claimed, providerId(run), requestedModel(run), failureMapper.unexpected(exception)));
                return;
            }
            if (!lease.isValid()) {
                return;
            }
            AgentResultPolicy.Decision decision = resultPolicy.evaluate(version, executionResult.modelResponse());
            if (decision.accepted()) {
                completeSucceeded(claimed, executionResult.providerId(), executionResult.requestedModel(),
                        executionResult.modelResponse(), executionResult.steps(),
                        executionResult.progressPersisted(), decision);
            } else {
                completeFailed(claimed, executionResult.providerId(), executionResult.requestedModel(),
                        new AgentFailure(decision.reasonCode(),
                                io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory.RESULT_POLICY,
                                false, decision.status(), "Agent result was rejected by policy"));
            }
        }
    }

    private void completeIfLeaseValid(AgentRunLeaseHeartbeat.LeaseHandle lease, Runnable completion) {
        if (lease.isValid()) {
            completion.run();
        }
    }

    private void completeSucceeded(
            ClaimedRun claimed,
            long providerId,
            String requestedModel,
            ModelProviderResponse response,
            List<AgentExecutor.StepResult> steps,
            boolean progressPersisted,
            AgentResultPolicy.Decision decision
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            stateMachine.markSucceeded(
                    claimed.run(), true, decision.accepted(),
                    transition(claimed.attemptId(), "MODEL_COMPLETED", claimed.traceId(), now));
            if (!progressPersisted) {
                persistChildSteps(claimed, steps, now);
            }
            executionRepository.insertCheckpoint(
                    claimed.run().tenantCode(), claimed.run().id(), claimed.attemptId(), 1,
                    "MODEL_RESULT", outputSnapshot(response, decision), now);
            executionRepository.saveSucceeded(
                    claimed.run(),
                    claimed.attemptId(),
                    claimed.stepId(),
                    providerId,
                    requestedModel,
                    response,
                    outputSnapshot(response, decision),
                    lastTransition(claimed.run()));
            eventPublisher.completed(claimed.run(), outputSnapshot(response, decision));
            metrics.completed(ResultStatus.SUCCESS);
        });
    }

    /** Persist each logical executor step before the next model/tool step starts. */
    private void persistProgress(ClaimedRun claimed, int sequence, AgentExecutor.StepResult step) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            int ledgerSequence = sequence + 1;
            executionRepository.insertCompletedStep(
                    claimed.run().tenantCode(), claimed.run().id(), claimed.attemptId(), ledgerSequence,
                    step.stepType(), step.status(), step.errorCode(), now);
            executionRepository.insertCheckpoint(
                    claimed.run().tenantCode(), claimed.run().id(), claimed.attemptId(), ledgerSequence,
                    "STEP_COMPLETED", stepSnapshot(step), now);
        });
    }

    private void persistChildSteps(
            ClaimedRun claimed,
            List<AgentExecutor.StepResult> steps,
            Instant completedAt
    ) {
        int sequence = 2;
        for (AgentExecutor.StepResult step : steps) {
            executionRepository.insertCompletedStep(
                    claimed.run().tenantCode(), claimed.run().id(), claimed.attemptId(), sequence++,
                    step.stepType(), step.status(), step.errorCode(), completedAt);
            executionRepository.insertCheckpoint(
                    claimed.run().tenantCode(), claimed.run().id(), claimed.attemptId(), sequence - 1,
                    "STEP_COMPLETED", stepSnapshot(step), completedAt);
        }
    }

    private String stepSnapshot(AgentExecutor.StepResult step) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "stepType", step.stepType(),
                    "status", step.status(),
                    "errorCode", step.errorCode() == null ? "" : step.errorCode()));
        } catch (JsonProcessingException exception) {
            throw new AgentExecutionException(new AgentFailure(
                    "AGENT_CHECKPOINT_SNAPSHOT_ERROR",
                    AgentFailureCategory.EXECUTION_UNEXPECTED,
                    false, ResultStatus.FAILED, "Agent checkpoint could not be serialized"), exception);
        }
    }

    private void completeFailed(ClaimedRun claimed, long providerId, String requestedModel, AgentFailure failure) {
        String errorCode = failure.errorCode();
        ResultStatus resultStatus = failure.resultStatus();
        boolean retryable = failure.retryable();
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            boolean retriesRemaining = retryable
                    && claimed.attemptNumber() < maxAttempts
                    && now.isBefore(claimed.run().deadlineAt());
            Instant availableAt;
            if (retriesRemaining) {
                availableAt = now.plus(retryBackoffPolicy.delayFor(claimed.attemptNumber()));
                if (!availableAt.isBefore(claimed.run().deadlineAt())) {
                    retriesRemaining = false;
                }
            } else {
                availableAt = now;
            }

            if (!now.isBefore(claimed.run().deadlineAt())) {
                stateMachine.markTimedOut(
                        claimed.run(),
                        AgentTimeoutType.DEADLINE_EXCEEDED,
                        transition(claimed.attemptId(), "AGENT_DEADLINE_EXCEEDED", claimed.traceId(), now));
            } else if (retriesRemaining) {
                stateMachine.retry(
                        claimed.run(), AgentFailureDisposition.RETRYABLE, true,
                        transition(claimed.attemptId(), errorCode, claimed.traceId(), now));
            } else {
                stateMachine.markFailed(
                        claimed.run(),
                        retryable ? AgentFailureDisposition.RETRIES_EXHAUSTED
                                : AgentFailureDisposition.NON_RETRYABLE,
                        transition(claimed.attemptId(), errorCode, claimed.traceId(), now));
            }
            executionRepository.saveFailed(
                    claimed.run(),
                    claimed.attemptId(),
                    claimed.stepId(),
                    providerId,
                    requestedModel,
                    errorCode,
                    resultStatus,
                    availableAt,
                    lastTransition(claimed.run()));
            AgentRecoveryPolicy.Decision recovery = recoveryPolicy.decide(failure, retriesRemaining);
            executionRepository.insertRecoveryDecision(new AgentRecoveryDecision(
                    claimed.run().tenantCode(), claimed.run().id(), claimed.attemptId(), claimed.stepId(),
                    errorCode, recovery.failureCategory(), recovery.action(), retriesRemaining,
                    recovery.requiresHumanReview(), resultStatus, recovery.reason(), now));
            if (!retriesRemaining || !now.isBefore(claimed.run().deadlineAt())) {
                eventPublisher.completed(claimed.run(), null);
                metrics.completed(resultStatus);
            } else {
                metrics.retryScheduled(errorCode);
            }
        });
    }

    private long providerId(AgentRun run) {
        return versionRepository.findByVersionId(run.tenantCode(), run.agentVersionId())
                .map(AgentDefinitionVersion::providerId)
                .orElse(0L);
    }

    private String requestedModel(AgentRun run) {
        return versionRepository.findByVersionId(run.tenantCode(), run.agentVersionId())
                .map(version -> StringUtils.hasText(version.modelName())
                        ? version.modelName()
                        : providerRepository.findById(run.tenantCode(), version.providerId())
                                .map(AgentProvider::defaultModel)
                                .orElse("unknown"))
                .orElse("unknown");
    }

    private Duration remainingTimeout(AgentRun run, int configuredTimeoutSeconds) {
        Duration remaining = Duration.between(Instant.now(), run.deadlineAt());
        Duration configured = Duration.ofSeconds(configuredTimeoutSeconds);
        Duration timeout = remaining.compareTo(configured) < 0 ? remaining : configured;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new AgentExecutionException(new AgentFailure(
                    "AGENT_DEADLINE_EXCEEDED",
                    AgentFailureCategory.DEADLINE,
                    false, ResultStatus.FAILED, "Agent run deadline has been exceeded"));
        }
        return timeout;
    }

    private String extractInput(String inputSnapshotJson) {
        try {
            var input = objectMapper.readTree(inputSnapshotJson).path("input");
            if (input.isMissingNode() || input.isNull()) {
                return "";
            }
            return input.isTextual() ? input.textValue() : objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException exception) {
            throw new AgentExecutionException(new AgentFailure(
                    "AGENT_INPUT_SNAPSHOT_INVALID",
                    AgentFailureCategory.EXECUTION_UNEXPECTED,
                    false, ResultStatus.FAILED, "Agent input snapshot could not be read"), exception);
        }
    }

    private String extractNodeToolSet(String inputSnapshotJson) {
        try {
            var node = objectMapper.readTree(inputSnapshotJson).path("nodeToolSet");
            return node.isMissingNode() || node.isNull() ? null : objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new AgentExecutionException(new AgentFailure(
                    "AGENT_INPUT_SNAPSHOT_INVALID",
                    AgentFailureCategory.EXECUTION_UNEXPECTED,
                    false, ResultStatus.FAILED, "Agent input snapshot could not be read"), exception);
        }
    }

    private String outputSnapshot(ModelProviderResponse response, AgentResultPolicy.Decision decision) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "content", response.content(),
                    "model", response.actualModel() == null ? "" : response.actualModel(),
                    "finishReason", response.finishReason() == null ? "" : response.finishReason(),
                    "resultStatus", decision.status().name(),
                    "resultReason", decision.reasonCode()));
        } catch (JsonProcessingException exception) {
            throw new AgentExecutionException(new AgentFailure(
                    "AGENT_OUTPUT_SNAPSHOT_ERROR",
                    AgentFailureCategory.EXECUTION_UNEXPECTED,
                    false, ResultStatus.FAILED, "Agent output snapshot could not be serialized"), exception);
        }
    }

    private AgentRunTransitionContext transition(
            long attemptId,
            String reasonCode,
            String traceId,
            Instant occurredAt
    ) {
        return new AgentRunTransitionContext(
                attemptId,
                reasonCode,
                AgentRunOperatorType.WORKER,
                workerId,
                traceId,
                occurredAt);
    }

    private AgentRunStateTransition lastTransition(AgentRun run) {
        return run.stateHistory().getLast();
    }

    private record ClaimedRun(
            AgentRun run,
            long attemptId,
            int attemptNumber,
            long stepId,
            String traceId,
            String inputSnapshotJson
    ) {
    }
}

package io.github.illuseahashmap.agent.runtime.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersionRepository;
import io.github.illuseahashmap.agent.provider.application.ModelProviderRegistry;
import io.github.illuseahashmap.agent.provider.application.port.AgentCredentialResolver;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderFailureKind;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderRepository;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.agent.runtime.application.AgentRunWorkerService;
import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunExecutionRepository;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunExecutionSnapshot;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunEventPublisher;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailureDisposition;
import io.github.illuseahashmap.agent.runtime.domain.AgentRun;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunLease;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunOperatorType;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStateMachine;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStateTransition;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunTransitionContext;
import io.github.illuseahashmap.agent.runtime.domain.AgentTimeoutType;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class AgentRunWorkerServiceImpl implements AgentRunWorkerService {

    private final AgentRunExecutionRepository executionRepository;
    private final AgentDefinitionVersionRepository versionRepository;
    private final AgentProviderRepository providerRepository;
    private final AgentCredentialResolver credentialResolver;
    private final ModelProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final AgentOutputSchemaValidator outputSchemaValidator;
    private final AgentRunStateMachine stateMachine = new AgentRunStateMachine();
    private final String workerId;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final AgentRunEventPublisher eventPublisher;

    @Autowired
    public AgentRunWorkerServiceImpl(
            AgentRunExecutionRepository executionRepository,
            AgentDefinitionVersionRepository versionRepository,
            AgentProviderRepository providerRepository,
            AgentCredentialResolver credentialResolver,
            ModelProviderRegistry providerRegistry,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            AgentOutputSchemaValidator outputSchemaValidator,
            @Value("${workflow.agent.worker.id:local-worker}") String workerId,
            @Value("${workflow.agent.worker.lease-seconds:60}") long leaseSeconds,
            @Value("${workflow.agent.worker.max-attempts:3}") int maxAttempts,
            AgentRunEventPublisher eventPublisher
    ) {
        this.executionRepository = executionRepository;
        this.versionRepository = versionRepository;
        this.providerRepository = providerRepository;
        this.credentialResolver = credentialResolver;
        this.providerRegistry = providerRegistry;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.outputSchemaValidator = outputSchemaValidator;
        this.workerId = workerId + ":" + UUID.randomUUID();
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
        this.maxAttempts = maxAttempts;
        this.eventPublisher = eventPublisher;
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
        this(executionRepository, versionRepository, providerRepository, credentialResolver,
                providerRegistry, objectMapper, transactionTemplate,
                new AgentOutputSchemaValidator(objectMapper), workerId, leaseSeconds, maxAttempts,
                AgentRunEventPublisher.NOOP);
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
        return TrustedDataAccessContext.runAsSystemWorker(() ->
                transactionTemplate.execute(status -> executionRepository.recoverExpired(Instant.now())));
    }

    private ClaimedRun claim() {
        Instant now = Instant.now();
        AgentRunExecutionSnapshot snapshot = executionRepository.lockNextAvailable(now).orElse(null);
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
        return new ClaimedRun(
                run, attemptId, attemptNumber, stepId, traceId, snapshot.inputSnapshotJson());
    }

    private void execute(ClaimedRun claimed) {
        AgentRun run = claimed.run();
        long providerId;
        String model;
        ModelProviderResponse response;
        try {
            AgentDefinitionVersion version = versionRepository.findByVersionId(run.tenantCode(), run.agentVersionId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent version does not exist"));
            AgentProvider provider = providerRepository.findById(run.tenantCode(), version.providerId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent Provider does not exist"));
            providerId = provider.id();
            model = StringUtils.hasText(version.modelName()) ? version.modelName() : provider.defaultModel();
            String credential = provider.type() == AgentProviderType.OPENAI_COMPATIBLE
                    ? credentialResolver.resolve(run.tenantCode(), provider.id())
                    : "";
            String userInput = extractInput(claimed.inputSnapshotJson());
            outputSchemaValidator.validateInput(version.inputSchema(), userInput);
            response = providerRegistry.require(provider.type()).invoke(
                    new ModelProviderRequest(
                            provider.baseUrl(),
                            credential,
                            model,
                            version.systemPrompt(),
                            userInput,
                            remainingTimeout(run, version.timeoutSeconds()),
                            claimed.traceId()));
            outputSchemaValidator.validateOutput(version.outputSchema(), response.content());
        } catch (ModelProviderException exception) {
            completeFailed(
                    claimed,
                    providerId(run),
                    requestedModel(run),
                    exception.errorCode(),
                    exception.failureKind() != ModelProviderFailureKind.PERMANENT);
            return;
        } catch (BusinessException exception) {
            completeFailed(claimed, providerId(run), requestedModel(run), "AGENT_CONFIGURATION_ERROR", false);
            return;
        } catch (RuntimeException exception) {
            completeFailed(claimed, providerId(run), requestedModel(run), "AGENT_EXECUTION_ERROR", false);
            return;
        }
        completeSucceeded(claimed, providerId, model, response);
    }

    private void completeSucceeded(
            ClaimedRun claimed,
            long providerId,
            String requestedModel,
            ModelProviderResponse response
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            stateMachine.markSucceeded(
                    claimed.run(), true, true,
                    transition(claimed.attemptId(), "MODEL_COMPLETED", claimed.traceId(), now));
            executionRepository.saveSucceeded(
                    claimed.run(),
                    claimed.attemptId(),
                    claimed.stepId(),
                    providerId,
                    requestedModel,
                    response,
                    outputSnapshot(response),
                    lastTransition(claimed.run()));
            eventPublisher.completed(claimed.run(), outputSnapshot(response));
        });
    }

    private void completeFailed(
            ClaimedRun claimed,
            long providerId,
            String requestedModel,
            String errorCode,
            boolean retryable
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            boolean retriesRemaining = retryable
                    && claimed.attemptNumber() < maxAttempts
                    && now.isBefore(claimed.run().deadlineAt());
            Instant availableAt;
            if (retriesRemaining) {
                availableAt = now.plusSeconds(backoffSeconds(claimed.attemptNumber()));
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
                    availableAt,
                    lastTransition(claimed.run()));
            if (!retriesRemaining || !now.isBefore(claimed.run().deadlineAt())) {
                eventPublisher.completed(claimed.run(), null);
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
            throw new ModelProviderException(
                    "AGENT_DEADLINE_EXCEEDED", ModelProviderFailureKind.PERMANENT,
                    "Agent run deadline has been exceeded");
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
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Unable to read Agent input", exception);
        }
    }

    private String outputSnapshot(ModelProviderResponse response) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "content", response.content(),
                    "model", response.actualModel() == null ? "" : response.actualModel(),
                    "finishReason", response.finishReason() == null ? "" : response.finishReason()));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Unable to serialize Agent output", exception);
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

    private long backoffSeconds(int attemptNumber) {
        return Math.min(30L, 1L << Math.min(attemptNumber - 1, 5));
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

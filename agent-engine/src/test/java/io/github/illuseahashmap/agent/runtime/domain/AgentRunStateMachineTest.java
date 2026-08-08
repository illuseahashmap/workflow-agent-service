package io.github.illuseahashmap.agent.runtime.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentRunStateMachineTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-08T01:00:00Z");
    private static final Instant DEADLINE_AT = Instant.parse("2026-08-08T01:10:00Z");
    private static final long ATTEMPT_ID = 2001L;

    private final AgentRunStateMachine stateMachine = new AgentRunStateMachine();

    @Test
    void startsQueuedRunWithValidLeaseAndRecordsAuditHistory() {
        AgentRun run = queuedRun();
        Instant startedAt = CREATED_AT.plusSeconds(10);
        AgentRunTransitionContext context = workerContext(ATTEMPT_ID, "LEASE_ACQUIRED", startedAt);

        stateMachine.startLease(
                run,
                new AgentRunLease(ATTEMPT_ID, "worker-1", startedAt.plusSeconds(60)),
                context);

        assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(run.currentAttemptId()).isEqualTo(ATTEMPT_ID);
        assertThat(run.leaseOwner()).isEqualTo("worker-1");
        assertThat(run.leaseExpiresAt()).isEqualTo(startedAt.plusSeconds(60));
        assertThat(run.startedAt()).isEqualTo(startedAt);
        assertThat(run.stateHistory()).containsExactly(new AgentRunStateTransition(
                "tenant-a",
                1001L,
                ATTEMPT_ID,
                AgentRunStatus.QUEUED,
                AgentRunStatus.RUNNING,
                "LEASE_ACQUIRED",
                AgentRunOperatorType.WORKER,
                "worker-1",
                "trace-1",
                startedAt));
    }

    @Test
    void rejectsExpiredLeaseWithoutChangingRun() {
        AgentRun run = queuedRun();
        Instant transitionAt = CREATED_AT.plusSeconds(10);

        assertThatThrownBy(() -> stateMachine.startLease(
                run,
                new AgentRunLease(ATTEMPT_ID, "worker-1", transitionAt),
                workerContext(ATTEMPT_ID, "LEASE_ACQUIRED", transitionAt)))
                .isInstanceOf(AgentRunTransitionException.class)
                .hasMessage("Lease must still be valid when execution starts");

        assertThat(run.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(run.stateHistory()).isEmpty();
    }

    @Test
    void succeedsOnlyAfterRequiredStepsAndResultPolicyPass() {
        AgentRun run = runningRun();
        Instant completedAt = CREATED_AT.plusSeconds(30);

        stateMachine.markSucceeded(
                run,
                true,
                true,
                workerContext(ATTEMPT_ID, "RESULT_ACCEPTED", completedAt));

        assertThat(run.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(run.completedAt()).isEqualTo(completedAt);
        assertThat(run.leaseOwner()).isNull();
        assertThat(run.leaseExpiresAt()).isNull();
        assertThat(run.stateHistory()).hasSize(2);
    }

    @Test
    void rejectsSuccessWhenRequiredStepFailed() {
        AgentRun run = runningRun();

        assertThatThrownBy(() -> stateMachine.markSucceeded(
                run,
                false,
                true,
                workerContext(ATTEMPT_ID, "RESULT_ACCEPTED", CREATED_AT.plusSeconds(30))))
                .isInstanceOf(AgentRunTransitionException.class)
                .hasMessage("Required Agent steps must succeed before completion");

        assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(run.stateHistory()).hasSize(1);
    }

    @Test
    void rejectsSuccessFromQueuedState() {
        AgentRun run = queuedRun();

        assertThatThrownBy(() -> stateMachine.markSucceeded(
                run,
                true,
                true,
                workerContext(ATTEMPT_ID, "RESULT_ACCEPTED", CREATED_AT.plusSeconds(30))))
                .isInstanceOf(AgentRunTransitionException.class)
                .hasMessage("Cannot markSucceeded when Agent run is QUEUED");
    }

    @Test
    void failsRunWhenFailureIsNonRetryable() {
        AgentRun run = runningRun();

        stateMachine.markFailed(
                run,
                AgentFailureDisposition.NON_RETRYABLE,
                workerContext(ATTEMPT_ID, "OUTPUT_SCHEMA_INVALID", CREATED_AT.plusSeconds(30)));

        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.stateHistory().getLast().reasonCode()).isEqualTo("OUTPUT_SCHEMA_INVALID");
    }

    @Test
    void retryableFailureMustNotBeMarkedFailedBeforeRetryPolicyIsExhausted() {
        AgentRun run = runningRun();

        assertThatThrownBy(() -> stateMachine.markFailed(
                run,
                AgentFailureDisposition.RETRYABLE,
                workerContext(ATTEMPT_ID, "PROVIDER_UNAVAILABLE", CREATED_AT.plusSeconds(30))))
                .isInstanceOf(AgentRunTransitionException.class)
                .hasMessage("A retryable failure must be retried or exhausted before failure");
    }

    @Test
    void retriesRunningAttemptWhenPolicyAllowsIt() {
        AgentRun run = runningRun();

        stateMachine.retry(
                run,
                AgentFailureDisposition.RETRYABLE,
                true,
                workerContext(ATTEMPT_ID, "PROVIDER_UNAVAILABLE", CREATED_AT.plusSeconds(30)));

        assertThat(run.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(run.currentAttemptId()).isNull();
        assertThat(run.leaseOwner()).isNull();
        assertThat(run.leaseExpiresAt()).isNull();
        assertThat(run.stateHistory().getLast().newStatus()).isEqualTo(AgentRunStatus.QUEUED);
    }

    @Test
    void rejectsRetryWhenNoAttemptsRemain() {
        AgentRun run = runningRun();

        assertThatThrownBy(() -> stateMachine.retry(
                run,
                AgentFailureDisposition.RETRYABLE,
                false,
                workerContext(ATTEMPT_ID, "PROVIDER_UNAVAILABLE", CREATED_AT.plusSeconds(30))))
                .isInstanceOf(AgentRunTransitionException.class)
                .hasMessage("Retry policy has no remaining attempts");
    }

    @Test
    void marksProviderTimeoutBeforeOverallDeadline() {
        AgentRun run = runningRun();

        stateMachine.markTimedOut(
                run,
                AgentTimeoutType.PROVIDER_TIMEOUT,
                workerContext(ATTEMPT_ID, "PROVIDER_TIMEOUT", CREATED_AT.plusSeconds(30)));

        assertThat(run.status()).isEqualTo(AgentRunStatus.TIMED_OUT);
    }

    @Test
    void rejectsDeadlineTimeoutBeforeDeadline() {
        AgentRun run = runningRun();

        assertThatThrownBy(() -> stateMachine.markTimedOut(
                run,
                AgentTimeoutType.DEADLINE_EXCEEDED,
                workerContext(ATTEMPT_ID, "DEADLINE_EXCEEDED", CREATED_AT.plusSeconds(30))))
                .isInstanceOf(AgentRunTransitionException.class)
                .hasMessage("Agent run deadline has not been exceeded");
    }

    @Test
    void cancelsQueuedAndRunningRuns() {
        AgentRun queued = queuedRun();
        AgentRun running = runningRun();

        stateMachine.cancel(queued, systemContext(null, "USER_CANCELLED", CREATED_AT.plusSeconds(20)));
        stateMachine.cancel(
                running,
                workerContext(ATTEMPT_ID, "CANCELLATION_OBSERVED", CREATED_AT.plusSeconds(20)));

        assertThat(queued.status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(running.status()).isEqualTo(AgentRunStatus.CANCELLED);
    }

    @Test
    void rejectsTransitionForStaleAttempt() {
        AgentRun run = runningRun();

        assertThatThrownBy(() -> stateMachine.markSucceeded(
                run,
                true,
                true,
                workerContext(9999L, "LATE_RESULT", CREATED_AT.plusSeconds(30))))
                .isInstanceOf(AgentRunTransitionException.class)
                .hasMessage("Transition attempt must match the current Agent run attempt");
    }

    @Test
    void terminalCommandsAreIdempotentAndDoNotAppendHistory() {
        AgentRun run = runningRun();
        stateMachine.markSucceeded(
                run,
                true,
                true,
                workerContext(ATTEMPT_ID, "RESULT_ACCEPTED", CREATED_AT.plusSeconds(30)));
        int historySize = run.stateHistory().size();

        stateMachine.markFailed(
                run,
                AgentFailureDisposition.NON_RETRYABLE,
                workerContext(ATTEMPT_ID, "LATE_FAILURE", CREATED_AT.plusSeconds(40)));
        stateMachine.cancel(
                run,
                workerContext(ATTEMPT_ID, "LATE_CANCELLATION", CREATED_AT.plusSeconds(40)));

        assertThat(run.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(run.stateHistory()).hasSize(historySize);
    }

    @Test
    void rejectsTransitionThatMovesAuditTimeBackwards() {
        AgentRun run = runningRun();

        assertThatThrownBy(() -> stateMachine.markSucceeded(
                run,
                true,
                true,
                workerContext(ATTEMPT_ID, "RESULT_ACCEPTED", CREATED_AT.plusSeconds(5))))
                .isInstanceOf(AgentRunTransitionException.class)
                .hasMessage("Transition time must not precede the current Agent run state");
    }

    private AgentRun queuedRun() {
        return AgentRun.queued(1001L, "tenant-a", 3001L, "idempotency-1", DEADLINE_AT, CREATED_AT);
    }

    private AgentRun runningRun() {
        AgentRun run = queuedRun();
        Instant startedAt = CREATED_AT.plusSeconds(10);
        stateMachine.startLease(
                run,
                new AgentRunLease(ATTEMPT_ID, "worker-1", startedAt.plusSeconds(60)),
                workerContext(ATTEMPT_ID, "LEASE_ACQUIRED", startedAt));
        return run;
    }

    private AgentRunTransitionContext workerContext(long attemptId, String reasonCode, Instant occurredAt) {
        return new AgentRunTransitionContext(
                attemptId,
                reasonCode,
                AgentRunOperatorType.WORKER,
                "worker-1",
                "trace-1",
                occurredAt);
    }

    private AgentRunTransitionContext systemContext(Long attemptId, String reasonCode, Instant occurredAt) {
        return new AgentRunTransitionContext(
                attemptId,
                reasonCode,
                AgentRunOperatorType.SYSTEM,
                "agent-runtime",
                "trace-1",
                occurredAt);
    }
}

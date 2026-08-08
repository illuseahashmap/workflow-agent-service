package io.github.illuseahashmap.agent.runtime.domain;

import java.util.Objects;

/**
 * The only domain service allowed to advance an {@link AgentRun} lifecycle.
 */
public final class AgentRunStateMachine {

    public AgentRun startLease(
            AgentRun run,
            AgentRunLease lease,
            AgentRunTransitionContext context
    ) {
        requireInputs(run, context);
        Objects.requireNonNull(lease, "lease must not be null");
        requireStatus(run, AgentRunStatus.QUEUED, "startLease");
        if (context.attemptId() == null || context.attemptId() != lease.attemptId()) {
            throw new AgentRunTransitionException("Transition attempt must match the leased attempt");
        }
        if (!lease.expiresAt().isAfter(context.occurredAt())) {
            throw new AgentRunTransitionException("Lease must still be valid when execution starts");
        }
        if (lease.expiresAt().isAfter(run.deadlineAt())) {
            throw new AgentRunTransitionException("Lease must not exceed the Agent run deadline");
        }
        if (!context.occurredAt().isBefore(run.deadlineAt())) {
            throw new AgentRunTransitionException("An expired Agent run cannot start");
        }
        run.startLease(lease, context);
        return run;
    }

    public AgentRun markSucceeded(
            AgentRun run,
            boolean requiredStepsSucceeded,
            boolean resultPolicyAccepted,
            AgentRunTransitionContext context
    ) {
        requireInputs(run, context);
        if (run.status().isTerminal()) {
            return run;
        }
        requireRunningAttempt(run, context, "markSucceeded");
        if (!requiredStepsSucceeded) {
            throw new AgentRunTransitionException("Required Agent steps must succeed before completion");
        }
        if (!resultPolicyAccepted) {
            throw new AgentRunTransitionException("Agent result policy must accept the result before completion");
        }
        run.finish(AgentRunStatus.SUCCEEDED, context);
        return run;
    }

    public AgentRun markFailed(
            AgentRun run,
            AgentFailureDisposition disposition,
            AgentRunTransitionContext context
    ) {
        requireInputs(run, context);
        Objects.requireNonNull(disposition, "disposition must not be null");
        if (run.status().isTerminal()) {
            return run;
        }
        requireRunningAttempt(run, context, "markFailed");
        if (disposition == AgentFailureDisposition.RETRYABLE) {
            throw new AgentRunTransitionException("A retryable failure must be retried or exhausted before failure");
        }
        run.finish(AgentRunStatus.FAILED, context);
        return run;
    }

    public AgentRun markTimedOut(
            AgentRun run,
            AgentTimeoutType timeoutType,
            AgentRunTransitionContext context
    ) {
        requireInputs(run, context);
        Objects.requireNonNull(timeoutType, "timeoutType must not be null");
        if (run.status().isTerminal()) {
            return run;
        }
        requireRunningAttempt(run, context, "markTimedOut");
        if (timeoutType == AgentTimeoutType.DEADLINE_EXCEEDED
                && context.occurredAt().isBefore(run.deadlineAt())) {
            throw new AgentRunTransitionException("Agent run deadline has not been exceeded");
        }
        run.finish(AgentRunStatus.TIMED_OUT, context);
        return run;
    }

    public AgentRun cancel(AgentRun run, AgentRunTransitionContext context) {
        requireInputs(run, context);
        if (run.status().isTerminal()) {
            return run;
        }
        if (run.status() == AgentRunStatus.RUNNING) {
            requireAttemptMatches(run, context);
        } else {
            requireStatus(run, AgentRunStatus.QUEUED, "cancel");
        }
        run.finish(AgentRunStatus.CANCELLED, context);
        return run;
    }

    public AgentRun retry(
            AgentRun run,
            AgentFailureDisposition disposition,
            boolean retriesRemaining,
            AgentRunTransitionContext context
    ) {
        requireInputs(run, context);
        Objects.requireNonNull(disposition, "disposition must not be null");
        requireRunningAttempt(run, context, "retry");
        if (disposition != AgentFailureDisposition.RETRYABLE) {
            throw new AgentRunTransitionException("Only a retryable failure can schedule another attempt");
        }
        if (!retriesRemaining) {
            throw new AgentRunTransitionException("Retry policy has no remaining attempts");
        }
        run.requeue(context);
        return run;
    }

    private void requireInputs(AgentRun run, AgentRunTransitionContext context) {
        Objects.requireNonNull(run, "run must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (context.occurredAt().isBefore(run.updatedAt())) {
            throw new AgentRunTransitionException("Transition time must not precede the current Agent run state");
        }
    }

    private void requireRunningAttempt(
            AgentRun run,
            AgentRunTransitionContext context,
            String command
    ) {
        requireStatus(run, AgentRunStatus.RUNNING, command);
        requireAttemptMatches(run, context);
    }

    private void requireAttemptMatches(AgentRun run, AgentRunTransitionContext context) {
        if (context.attemptId() == null || !context.attemptId().equals(run.currentAttemptId())) {
            throw new AgentRunTransitionException("Transition attempt must match the current Agent run attempt");
        }
    }

    private void requireStatus(AgentRun run, AgentRunStatus expected, String command) {
        if (run.status() != expected) {
            throw new AgentRunTransitionException(
                    "Cannot " + command + " when Agent run is " + run.status());
        }
    }
}

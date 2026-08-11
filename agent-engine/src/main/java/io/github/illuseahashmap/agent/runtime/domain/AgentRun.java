package io.github.illuseahashmap.agent.runtime.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregate root and audit ledger for one logical Agent execution.
 */
public final class AgentRun {

    private final long id;
    private final String tenantCode;
    private final long agentVersionId;
    private final String idempotencyKey;
    private final Instant deadlineAt;
    private final Instant createdAt;
    private final String processInstanceId;
    private final String executionId;
    private final String activityId;
    private final List<AgentRunStateTransition> stateHistory;
    private AgentRunStatus status;
    private Long currentAttemptId;
    private String leaseOwner;
    private Instant leaseExpiresAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant updatedAt;

    private AgentRun(
            long id,
            String tenantCode,
            long agentVersionId,
            String idempotencyKey,
            Instant deadlineAt,
            Instant createdAt,
            String processInstanceId,
            String executionId,
            String activityId
    ) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new IllegalArgumentException("tenantCode must not be blank");
        }
        if (agentVersionId <= 0) {
            throw new IllegalArgumentException("agentVersionId must be positive");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (deadlineAt == null || createdAt == null || !deadlineAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("deadlineAt must be after createdAt");
        }
        this.id = id;
        this.tenantCode = tenantCode;
        this.agentVersionId = agentVersionId;
        this.idempotencyKey = idempotencyKey;
        this.deadlineAt = deadlineAt;
        this.createdAt = createdAt;
        this.processInstanceId = processInstanceId;
        this.executionId = executionId;
        this.activityId = activityId;
        this.updatedAt = createdAt;
        this.status = AgentRunStatus.QUEUED;
        this.stateHistory = new ArrayList<>();
    }

    public static AgentRun queued(
            long id,
            String tenantCode,
            long agentVersionId,
            String idempotencyKey,
            Instant deadlineAt,
            Instant createdAt
    ) {
        return new AgentRun(id, tenantCode, agentVersionId, idempotencyKey, deadlineAt, createdAt,
                null, null, null);
    }

    public static AgentRun queued(
            long id, String tenantCode, long agentVersionId, String idempotencyKey,
            Instant deadlineAt, Instant createdAt, String processInstanceId,
            String executionId, String activityId
    ) {
        return new AgentRun(id, tenantCode, agentVersionId, idempotencyKey, deadlineAt, createdAt,
                processInstanceId, executionId, activityId);
    }

    public static AgentRun restore(
            long id,
            String tenantCode,
            long agentVersionId,
            String idempotencyKey,
            AgentRunStatus status,
            Long currentAttemptId,
            String leaseOwner,
            Instant leaseExpiresAt,
            Instant deadlineAt,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt,
            String processInstanceId,
            String executionId,
            String activityId
    ) {
        AgentRun run = new AgentRun(id, tenantCode, agentVersionId, idempotencyKey, deadlineAt, createdAt,
                processInstanceId, executionId, activityId);
        run.status = status;
        run.currentAttemptId = currentAttemptId;
        run.leaseOwner = leaseOwner;
        run.leaseExpiresAt = leaseExpiresAt;
        run.startedAt = startedAt;
        run.completedAt = completedAt;
        run.updatedAt = updatedAt;
        return run;
    }

    void startLease(AgentRunLease lease, AgentRunTransitionContext context) {
        currentAttemptId = lease.attemptId();
        leaseOwner = lease.owner();
        leaseExpiresAt = lease.expiresAt();
        if (startedAt == null) {
            startedAt = context.occurredAt();
        }
        transitionTo(AgentRunStatus.RUNNING, context);
    }

    void finish(AgentRunStatus targetStatus, AgentRunTransitionContext context) {
        clearLease();
        completedAt = context.occurredAt();
        transitionTo(targetStatus, context);
    }

    void requeue(AgentRunTransitionContext context) {
        clearLease();
        transitionTo(AgentRunStatus.QUEUED, context);
        currentAttemptId = null;
    }

    private void transitionTo(AgentRunStatus targetStatus, AgentRunTransitionContext context) {
        AgentRunStatus previousStatus = status;
        status = targetStatus;
        updatedAt = context.occurredAt();
        stateHistory.add(new AgentRunStateTransition(
                tenantCode,
                id,
                context.attemptId(),
                previousStatus,
                targetStatus,
                context.reasonCode(),
                context.operatorType(),
                context.operatorId(),
                context.traceId(),
                context.occurredAt()));
    }

    private void clearLease() {
        leaseOwner = null;
        leaseExpiresAt = null;
    }

    public long id() {
        return id;
    }

    public String tenantCode() {
        return tenantCode;
    }

    public long agentVersionId() {
        return agentVersionId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public AgentRunStatus status() {
        return status;
    }

    public Long currentAttemptId() {
        return currentAttemptId;
    }

    public String leaseOwner() {
        return leaseOwner;
    }

    public Instant leaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Instant deadlineAt() {
        return deadlineAt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public String processInstanceId() { return processInstanceId; }
    public String executionId() { return executionId; }
    public String activityId() { return activityId; }

    public List<AgentRunStateTransition> stateHistory() {
        return List.copyOf(stateHistory);
    }
}

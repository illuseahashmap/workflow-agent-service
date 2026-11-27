package io.github.illuseahashmap.workflow.assignment.domain;

import java.time.Duration;

public interface AssignmentFallbackCommandRepository {

    void enqueue(String tenantId, String taskId, String processInstanceId, AssignmentFallbackAction action);

    AssignmentFallbackCommand claimNext(Duration processingTimeout);

    void markSucceeded(long commandId);

    void reschedule(long commandId, Duration delay, String failureMessage);

    void markFailed(long commandId, String failureMessage);
}

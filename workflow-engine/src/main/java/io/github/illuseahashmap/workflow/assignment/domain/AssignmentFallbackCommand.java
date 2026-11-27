package io.github.illuseahashmap.workflow.assignment.domain;

public record AssignmentFallbackCommand(
        long id,
        String tenantId,
        String taskId,
        String processInstanceId,
        AssignmentFallbackAction action,
        int attemptCount) {
}

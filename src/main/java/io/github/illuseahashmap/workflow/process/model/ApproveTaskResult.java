package io.github.illuseahashmap.workflow.process.model;

import java.util.List;

public record ApproveTaskResult(
        String completedTaskId,
        String processInstanceId,
        boolean processEnded,
        List<TaskView> nextTasks
) {
}

package io.github.illuseahashmap.workflow.process.interfaces.dto;

import java.util.List;

public record ApproveTaskResult(
        String completedTaskId,
        String processInstanceId,
        boolean processEnded,
        List<TaskView> nextTasks
) {
}

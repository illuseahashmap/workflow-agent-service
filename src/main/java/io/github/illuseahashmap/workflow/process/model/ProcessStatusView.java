package io.github.illuseahashmap.workflow.process.model;

import java.util.List;

public record ProcessStatusView(
        String processInstanceId,
        String processDefinitionId,
        String businessKey,
        String status,
        List<TaskView> activeTasks
) {
}

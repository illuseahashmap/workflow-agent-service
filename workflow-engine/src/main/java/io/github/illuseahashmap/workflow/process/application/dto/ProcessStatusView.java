package io.github.illuseahashmap.workflow.process.application.dto;

import java.util.List;

public record ProcessStatusView(
        String processInstanceId,
        String processDefinitionId,
        String businessKey,
        String status,
        List<TaskView> activeTasks
) {
}

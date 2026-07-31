package io.github.illuseahashmap.workflow.process.model;

import java.util.List;

public record StartProcessResult(
        String processInstanceId,
        String processDefinitionId,
        String processDefinitionKey,
        String businessKey,
        List<TaskView> activeTasks
) {
}

package io.github.illuseahashmap.workflow.process.application.port;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only application port exposed to controlled Agent tools.
 * The port deliberately returns a bounded business view instead of Flowable objects.
 */
public interface WorkflowProcessContextReader {

    Optional<WorkflowProcessContext> read(String tenantCode, String processInstanceId);

    record WorkflowProcessContext(
            String processInstanceId,
            String processDefinitionKey,
            String processDefinitionName,
            String businessKey,
            String status,
            List<ActiveTask> activeTasks,
            Map<String, Object> businessVariables
    ) {
        public WorkflowProcessContext {
            activeTasks = List.copyOf(activeTasks == null ? List.of() : activeTasks);
            businessVariables = Collections.unmodifiableMap(new LinkedHashMap<>(
                    businessVariables == null ? Map.of() : businessVariables));
        }
    }

    record ActiveTask(String taskDefinitionKey, String taskName, String assignee) {
    }
}

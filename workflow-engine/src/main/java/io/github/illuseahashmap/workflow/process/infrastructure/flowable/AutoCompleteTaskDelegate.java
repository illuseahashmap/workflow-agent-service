package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component("autoCompleteTaskDelegate")
public class AutoCompleteTaskDelegate implements JavaDelegate {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutoCompleteTaskDelegate.class);

    private final TaskService taskService;
    private Expression targetTaskKey;

    public AutoCompleteTaskDelegate(TaskService taskService) {
        this.taskService = taskService;
    }

    public void setTargetTaskKey(Expression targetTaskKey) {
        this.targetTaskKey = targetTaskKey;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String taskKey = String.valueOf(targetTaskKey.getValue(execution));
        for (Task task : taskService.createTaskQuery()
                .processInstanceId(execution.getProcessInstanceId())
                .taskDefinitionKey(taskKey)
                .active()
                .list()) {
            completeIfActive(task.getId());
        }
    }

    private void completeIfActive(String taskId) {
        if (taskService.createTaskQuery().taskId(taskId).singleResult() == null) {
            return;
        }
        try {
            taskService.complete(taskId);
        } catch (FlowableObjectNotFoundException exception) {
            LOGGER.info("Auto-complete skipped because task no longer exists: taskId={}", taskId);
        }
    }
}

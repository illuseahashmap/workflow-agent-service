package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import org.flowable.engine.TaskService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AutoCompleteExecutor {

    private final TaskService taskService;

    public AutoCompleteExecutor(TaskService taskService) {
        this.taskService = taskService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void complete(String taskId) {
        if (taskService.createTaskQuery().taskId(taskId).singleResult() != null) {
            taskService.complete(taskId);
        }
    }
}

package io.github.illuseahashmap.workflow.assignment.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.port.ProcessInstanceLock;
import java.util.Map;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AssignmentFallbackExecutor {

    private static final String AUTO_COMPLETE_COMMENT = "Task automatically completed because no handler was resolved";
    private static final String AUTO_REJECT_COMMENT = "Task automatically rejected because no handler was resolved";

    private final TaskService taskService;
    private final ProcessInstanceLock processInstanceLock;

    public AssignmentFallbackExecutor(TaskService taskService, ProcessInstanceLock processInstanceLock) {
        this.taskService = taskService;
        this.processInstanceLock = processInstanceLock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void autoComplete(String taskId, String processInstanceId) {
        processInstanceLock.execute(processInstanceId, () -> {
            Task task = findTask(taskId);
            if (task != null) {
                taskService.addComment(taskId, processInstanceId, "auto-complete", AUTO_COMPLETE_COMMENT);
                taskService.complete(taskId, Map.of("autoComplete", true));
            }
            return null;
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void autoReject(String taskId, String processInstanceId) {
        processInstanceLock.execute(processInstanceId, () -> {
            Task task = findTask(taskId);
            if (task != null) {
                taskService.addComment(taskId, processInstanceId, "auto-reject", AUTO_REJECT_COMMENT);
                taskService.complete(taskId, Map.of("approved", false, "autoReject", true));
            }
            return null;
        });
    }

    private Task findTask(String taskId) {
        return taskService.createTaskQuery().taskId(taskId).singleResult();
    }
}

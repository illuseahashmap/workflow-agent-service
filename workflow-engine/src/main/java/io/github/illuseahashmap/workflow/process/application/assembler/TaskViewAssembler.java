package io.github.illuseahashmap.workflow.process.application.assembler;

import io.github.illuseahashmap.workflow.process.interfaces.dto.TaskView;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TaskViewAssembler {

    private final TaskService taskService;

    public TaskViewAssembler(TaskService taskService) {
        this.taskService = taskService;
    }

    public TaskView fromActiveTask(Task task) {
        List<IdentityLink> identityLinks = taskService.getIdentityLinksForTask(task.getId());
        return new TaskView(
                task.getId(),
                task.getProcessInstanceId(),
                task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(),
                task.getName(),
                task.getAssignee(),
                candidateUsers(identityLinks),
                candidateGroups(identityLinks),
                "ACTIVE",
                toOffsetDateTime(task.getCreateTime()),
                null,
                null
        );
    }

    public TaskView fromHistoricTask(HistoricTaskInstance task) {
        return new TaskView(
                task.getId(),
                task.getProcessInstanceId(),
                task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(),
                task.getName(),
                task.getAssignee(),
                List.of(),
                List.of(),
                "COMPLETED_OR_NOT_FOUND",
                toOffsetDateTime(task.getCreateTime()),
                toOffsetDateTime(task.getEndTime()),
                task.getDeleteReason()
        );
    }

    public boolean canOperate(Task task, String currentAssignee, List<String> currentCandidateGroups) {
        if (!StringUtils.hasText(currentAssignee)) {
            return false;
        }
        if (StringUtils.hasText(task.getAssignee())) {
            return currentAssignee.equals(task.getAssignee());
        }
        List<IdentityLink> identityLinks = taskService.getIdentityLinksForTask(task.getId());
        boolean candidateUserMatched = candidateUsers(identityLinks).contains(currentAssignee);
        boolean candidateGroupMatched = candidateGroups(identityLinks).stream()
                .anyMatch(group -> currentCandidateGroups != null && currentCandidateGroups.contains(group));
        return candidateUserMatched || candidateGroupMatched;
    }

    public void claimIfNeeded(Task task, String currentAssignee) {
        if (!StringUtils.hasText(task.getAssignee())) {
            taskService.claim(task.getId(), currentAssignee);
        }
    }

    private List<String> candidateUsers(List<IdentityLink> identityLinks) {
        List<String> users = new ArrayList<>();
        for (IdentityLink identityLink : identityLinks) {
            if (IdentityLinkType.CANDIDATE.equals(identityLink.getType())
                    && StringUtils.hasText(identityLink.getUserId())) {
                users.add(identityLink.getUserId());
            }
        }
        return users;
    }

    private List<String> candidateGroups(List<IdentityLink> identityLinks) {
        List<String> groups = new ArrayList<>();
        for (IdentityLink identityLink : identityLinks) {
            if (IdentityLinkType.CANDIDATE.equals(identityLink.getType())
                    && StringUtils.hasText(identityLink.getGroupId())) {
                groups.add(identityLink.getGroupId());
            }
        }
        return groups;
    }

    private OffsetDateTime toOffsetDateTime(Date date) {
        return date == null ? null : date.toInstant().atOffset(ZoneOffset.UTC);
    }
}

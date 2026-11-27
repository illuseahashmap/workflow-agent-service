package io.github.illuseahashmap.workflow.assignment.infrastructure.flowable;

import io.github.illuseahashmap.workflow.assignment.application.port.ProcessDefinitionCatalog;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentType;
import java.util.List;
import java.util.Optional;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.springframework.stereotype.Component;

@Component
public class FlowableProcessDefinitionCatalog implements ProcessDefinitionCatalog {

    private final RepositoryService repositoryService;

    public FlowableProcessDefinitionCatalog(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    @Override
    public Optional<DefinitionInfo> findById(String tenantId, String processDefinitionId) {
        return Optional.ofNullable(repositoryService.createProcessDefinitionQuery()
                        .processDefinitionTenantId(tenantId)
                        .processDefinitionId(processDefinitionId)
                        .singleResult())
                .map(definition -> new DefinitionInfo(
                        definition.getId(), definition.getKey(), definition.getVersion()));
    }

    @Override
    public List<DefinitionInfo> findVersions(String tenantId, String processDefinitionKey) {
        return repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId)
                .processDefinitionKey(processDefinitionKey)
                .orderByProcessDefinitionVersion()
                .desc()
                .list()
                .stream()
                .map(definition -> new DefinitionInfo(
                        definition.getId(), definition.getKey(), definition.getVersion()))
                .toList();
    }

    @Override
    public AssignmentType expectedAssignmentType(String processDefinitionId, String taskDefinitionKey) {
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
        if (model == null || model.getMainProcess() == null) {
            throw new IllegalArgumentException("Process model does not exist");
        }
        FlowElement element = model.getMainProcess().getFlowElement(taskDefinitionKey, true);
        if (!(element instanceof UserTask userTask)) {
            throw new IllegalArgumentException("User task does not exist: " + taskDefinitionKey);
        }
        if (userTask.getLoopCharacteristics() != null) {
            return AssignmentType.COUNTERSIGN_USERS;
        }
        boolean users = userTask.getCandidateUsers() != null && !userTask.getCandidateUsers().isEmpty();
        boolean groups = userTask.getCandidateGroups() != null && !userTask.getCandidateGroups().isEmpty();
        if (users && groups) {
            return AssignmentType.MIXED;
        }
        if (users) {
            return AssignmentType.CANDIDATE_USERS;
        }
        if (groups) {
            return AssignmentType.CANDIDATE_GROUPS;
        }
        return AssignmentType.ASSIGNEE;
    }
}

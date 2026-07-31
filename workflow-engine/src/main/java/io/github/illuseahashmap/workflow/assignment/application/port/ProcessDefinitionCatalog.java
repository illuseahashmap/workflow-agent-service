package io.github.illuseahashmap.workflow.assignment.application.port;

import io.github.illuseahashmap.workflow.assignment.domain.AssignmentType;
import java.util.List;
import java.util.Optional;

public interface ProcessDefinitionCatalog {

    Optional<DefinitionInfo> findById(String tenantId, String processDefinitionId);

    List<DefinitionInfo> findVersions(String tenantId, String processDefinitionKey);

    AssignmentType expectedAssignmentType(String processDefinitionId, String taskDefinitionKey);

    record DefinitionInfo(String id, String key, int version) {
    }
}

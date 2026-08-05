package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionDiagramView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDiagramDataView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInstanceDetailView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInstanceSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.TerminateProcessRequest;
import io.github.illuseahashmap.workflow.shared.response.PageResult;

public interface WorkflowAdministrationService {

    byte[] generateProcessDiagram(String processInstanceId);

    ProcessDiagramDataView getProcessDiagramData(String processInstanceId);

    PageResult<ProcessDefinitionSummaryView> pageProcessDefinitions(
            Integer pageNum, Integer pageSize, String key, String name, String publishStatus);

    PageResult<ProcessInstanceSummaryView> pageProcessInstances(
            Integer pageNum, Integer pageSize, String definitionKey, String definitionName,
            String processInstanceId, String businessKey, String status);

    ProcessInstanceDetailView getProcessInstanceDetail(String processInstanceId);

    void terminateProcessInstance(TerminateProcessRequest request);

    ProcessDefinitionDiagramView getProcessDefinitionDiagram(
            String processDefinitionKey, Integer version, String processDefinitionId);

    void deleteProcessDefinitions(String processDefinitionKey);

    void deleteProcessDefinitionVersion(String processDefinitionKey, Integer version);
}

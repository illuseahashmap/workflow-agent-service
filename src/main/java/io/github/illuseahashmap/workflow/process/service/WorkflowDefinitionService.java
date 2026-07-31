package io.github.illuseahashmap.workflow.process.service;

import io.github.illuseahashmap.workflow.process.model.ActivateProcessVersionRequest;
import io.github.illuseahashmap.workflow.process.model.ActiveProcessVersionResult;
import io.github.illuseahashmap.workflow.process.model.DeployProcessRequest;
import io.github.illuseahashmap.workflow.process.model.DeployProcessResult;
import io.github.illuseahashmap.workflow.process.model.ProcessDefinitionView;
import java.util.List;

public interface WorkflowDefinitionService {

    DeployProcessResult deploy(DeployProcessRequest request);

    ActiveProcessVersionResult activate(ActivateProcessVersionRequest request);

    ActiveProcessVersionResult getActiveVersion(String processDefinitionKey);

    boolean exists(String processDefinitionKey);

    List<ProcessDefinitionView> list(String processDefinitionKey);

    ProcessDefinitionView getDefinition(String processDefinitionKey, Integer version);
}

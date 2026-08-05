package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.ActivateProcessVersionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ActiveProcessVersionResult;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionView;
import java.util.List;

public interface WorkflowDefinitionService {

    DeployProcessResult deploy(DeployProcessRequest request);

    ActiveProcessVersionResult activate(ActivateProcessVersionRequest request);

    ActiveProcessVersionResult getActiveVersion(String processDefinitionKey);

    boolean exists(String processDefinitionKey);

    List<ProcessDefinitionView> list(String processDefinitionKey);

    ProcessDefinitionView getDefinition(String processDefinitionKey, Integer version);
}

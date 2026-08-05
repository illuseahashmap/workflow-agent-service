package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.ApproveTaskRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ApproveTaskResult;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessStatusView;
import io.github.illuseahashmap.workflow.process.application.dto.ParticipantRequirementView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessParticipantRequirementsRequest;
import io.github.illuseahashmap.workflow.process.application.dto.RejectTaskRequest;
import io.github.illuseahashmap.workflow.process.application.dto.StartProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.StartProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.TaskView;
import io.github.illuseahashmap.workflow.process.application.dto.TaskParticipantRequirementsRequest;
import io.github.illuseahashmap.workflow.process.application.dto.TransferTaskRequest;
import java.util.List;

public interface WorkflowRuntimeService {

    StartProcessResult start(StartProcessRequest request);

    List<ParticipantRequirementView> getStartParticipantRequirements(
            ProcessParticipantRequirementsRequest request);

    ProcessStatusView getProcessStatus(String processInstanceId);

    TaskView getTaskStatus(String taskId);

    List<ParticipantRequirementView> getTaskParticipantRequirements(
            TaskParticipantRequirementsRequest request);

    ApproveTaskResult approve(ApproveTaskRequest request);

    ApproveTaskResult autoComplete(ApproveTaskRequest request);

    ApproveTaskResult reject(RejectTaskRequest request);

    TaskView transfer(TransferTaskRequest request);
}

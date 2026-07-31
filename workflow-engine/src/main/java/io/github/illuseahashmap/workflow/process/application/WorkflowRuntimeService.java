package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.interfaces.dto.ApproveTaskRequest;
import io.github.illuseahashmap.workflow.process.interfaces.dto.ApproveTaskResult;
import io.github.illuseahashmap.workflow.process.interfaces.dto.ProcessStatusView;
import io.github.illuseahashmap.workflow.process.interfaces.dto.RejectTaskRequest;
import io.github.illuseahashmap.workflow.process.interfaces.dto.StartProcessRequest;
import io.github.illuseahashmap.workflow.process.interfaces.dto.StartProcessResult;
import io.github.illuseahashmap.workflow.process.interfaces.dto.TaskView;
import io.github.illuseahashmap.workflow.process.interfaces.dto.TransferTaskRequest;

public interface WorkflowRuntimeService {

    StartProcessResult start(StartProcessRequest request);

    ProcessStatusView getProcessStatus(String processInstanceId);

    TaskView getTaskStatus(String taskId);

    ApproveTaskResult approve(ApproveTaskRequest request);

    ApproveTaskResult reject(RejectTaskRequest request);

    TaskView transfer(TransferTaskRequest request);
}

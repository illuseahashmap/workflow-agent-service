package io.github.illuseahashmap.workflow.process.service;

import io.github.illuseahashmap.workflow.process.model.ApproveTaskRequest;
import io.github.illuseahashmap.workflow.process.model.ApproveTaskResult;
import io.github.illuseahashmap.workflow.process.model.ProcessStatusView;
import io.github.illuseahashmap.workflow.process.model.RejectTaskRequest;
import io.github.illuseahashmap.workflow.process.model.StartProcessRequest;
import io.github.illuseahashmap.workflow.process.model.StartProcessResult;
import io.github.illuseahashmap.workflow.process.model.TaskView;
import io.github.illuseahashmap.workflow.process.model.TransferTaskRequest;

public interface WorkflowRuntimeService {

    StartProcessResult start(StartProcessRequest request);

    ProcessStatusView getProcessStatus(String processInstanceId);

    TaskView getTaskStatus(String taskId);

    ApproveTaskResult approve(ApproveTaskRequest request);

    ApproveTaskResult reject(RejectTaskRequest request);

    TaskView transfer(TransferTaskRequest request);
}

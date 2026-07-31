package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.ApproveTaskRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ApproveTaskResult;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessStatusView;
import io.github.illuseahashmap.workflow.process.application.dto.RejectTaskRequest;
import io.github.illuseahashmap.workflow.process.application.dto.StartProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.StartProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.TaskView;
import io.github.illuseahashmap.workflow.process.application.dto.TransferTaskRequest;

public interface WorkflowRuntimeService {

    StartProcessResult start(StartProcessRequest request);

    ProcessStatusView getProcessStatus(String processInstanceId);

    TaskView getTaskStatus(String taskId);

    ApproveTaskResult approve(ApproveTaskRequest request);

    ApproveTaskResult reject(RejectTaskRequest request);

    TaskView transfer(TransferTaskRequest request);
}

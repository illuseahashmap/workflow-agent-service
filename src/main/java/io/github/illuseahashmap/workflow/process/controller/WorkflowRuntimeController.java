package io.github.illuseahashmap.workflow.process.controller;

import io.github.illuseahashmap.workflow.common.response.ApiResponse;
import io.github.illuseahashmap.workflow.process.model.ApproveTaskRequest;
import io.github.illuseahashmap.workflow.process.model.ApproveTaskResult;
import io.github.illuseahashmap.workflow.process.model.ProcessStatusView;
import io.github.illuseahashmap.workflow.process.model.RejectTaskRequest;
import io.github.illuseahashmap.workflow.process.model.StartProcessRequest;
import io.github.illuseahashmap.workflow.process.model.StartProcessResult;
import io.github.illuseahashmap.workflow.process.model.TaskView;
import io.github.illuseahashmap.workflow.process.model.TransferTaskRequest;
import io.github.illuseahashmap.workflow.process.service.WorkflowRuntimeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/workflow")
public class WorkflowRuntimeController {

    private final WorkflowRuntimeService workflowRuntimeService;

    public WorkflowRuntimeController(WorkflowRuntimeService workflowRuntimeService) {
        this.workflowRuntimeService = workflowRuntimeService;
    }

    @PostMapping("/process/start")
    public ApiResponse<StartProcessResult> start(@Valid @RequestBody StartProcessRequest request) {
        return ApiResponse.ok(workflowRuntimeService.start(request));
    }

    @GetMapping("/process/status")
    public ApiResponse<ProcessStatusView> processStatus(@RequestParam String processInstanceId) {
        return ApiResponse.ok(workflowRuntimeService.getProcessStatus(processInstanceId));
    }

    @GetMapping("/task/status")
    public ApiResponse<TaskView> taskStatus(@RequestParam String taskId) {
        return ApiResponse.ok(workflowRuntimeService.getTaskStatus(taskId));
    }

    @PostMapping("/task/approve")
    public ApiResponse<ApproveTaskResult> approve(@Valid @RequestBody ApproveTaskRequest request) {
        return ApiResponse.ok(workflowRuntimeService.approve(request));
    }

    @PostMapping("/task/reject")
    public ApiResponse<ApproveTaskResult> reject(@Valid @RequestBody RejectTaskRequest request) {
        return ApiResponse.ok(workflowRuntimeService.reject(request));
    }

    @PostMapping("/task/transfer")
    public ApiResponse<TaskView> transfer(@Valid @RequestBody TransferTaskRequest request) {
        return ApiResponse.ok(workflowRuntimeService.transfer(request));
    }
}

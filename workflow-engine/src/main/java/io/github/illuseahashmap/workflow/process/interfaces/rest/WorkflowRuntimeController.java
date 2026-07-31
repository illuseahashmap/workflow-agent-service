package io.github.illuseahashmap.workflow.process.interfaces.rest;

import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.process.application.dto.ApproveTaskRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ApproveTaskResult;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessStatusView;
import io.github.illuseahashmap.workflow.process.application.dto.RejectTaskRequest;
import io.github.illuseahashmap.workflow.process.application.dto.StartProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.StartProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.TaskView;
import io.github.illuseahashmap.workflow.process.application.dto.TransferTaskRequest;
import io.github.illuseahashmap.workflow.process.application.WorkflowRuntimeService;
import io.github.illuseahashmap.workflow.process.application.WorkflowAdministrationService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionDiagramView;
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
    private final WorkflowAdministrationService administrationService;

    public WorkflowRuntimeController(WorkflowRuntimeService workflowRuntimeService,
                                     WorkflowAdministrationService administrationService) {
        this.workflowRuntimeService = workflowRuntimeService;
        this.administrationService = administrationService;
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

    @GetMapping("/process/definition/diagram")
    public ApiResponse<ProcessDefinitionDiagramView> definitionDiagram(
            @RequestParam String processDefinitionKey,
            @RequestParam(required = false) Integer version,
            @RequestParam(required = false) String processDefinitionId) {
        return ApiResponse.ok(administrationService.getProcessDefinitionDiagram(
                processDefinitionKey, version, processDefinitionId));
    }
}

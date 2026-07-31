package io.github.illuseahashmap.workflow.process.interfaces.rest;

import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.process.application.dto.ActivateProcessVersionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ActiveProcessVersionResult;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionView;
import io.github.illuseahashmap.workflow.process.application.WorkflowDefinitionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/workflow/management/process")
public class WorkflowManagementController {

    private final WorkflowDefinitionService workflowDefinitionService;

    public WorkflowManagementController(WorkflowDefinitionService workflowDefinitionService) {
        this.workflowDefinitionService = workflowDefinitionService;
    }

    @PostMapping("/deploy")
    public ApiResponse<DeployProcessResult> deploy(@Valid @RequestBody DeployProcessRequest request) {
        return ApiResponse.ok(workflowDefinitionService.deploy(request));
    }

    @PostMapping("/definition/activate")
    public ApiResponse<ActiveProcessVersionResult> activate(@Valid @RequestBody ActivateProcessVersionRequest request) {
        return ApiResponse.ok(workflowDefinitionService.activate(request));
    }

    @GetMapping("/definition/active")
    public ApiResponse<ActiveProcessVersionResult> active(@RequestParam String processDefinitionKey) {
        return ApiResponse.ok(workflowDefinitionService.getActiveVersion(processDefinitionKey));
    }

    @GetMapping("/definition/exists")
    public ApiResponse<Boolean> exists(@RequestParam String processDefinitionKey) {
        return ApiResponse.ok(workflowDefinitionService.exists(processDefinitionKey));
    }

    @GetMapping("/definitions")
    public ApiResponse<List<ProcessDefinitionView>> list(
            @RequestParam(required = false) String processDefinitionKey) {
        return ApiResponse.ok(workflowDefinitionService.list(processDefinitionKey));
    }

    @GetMapping("/definition")
    public ApiResponse<ProcessDefinitionView> detail(@RequestParam String processDefinitionKey,
                                                     @RequestParam(required = false) Integer version) {
        return ApiResponse.ok(workflowDefinitionService.getDefinition(processDefinitionKey, version));
    }
}

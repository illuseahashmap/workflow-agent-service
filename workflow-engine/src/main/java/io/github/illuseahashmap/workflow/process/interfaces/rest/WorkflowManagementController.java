package io.github.illuseahashmap.workflow.process.interfaces.rest;

import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.process.application.dto.ActivateProcessVersionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ActiveProcessVersionResult;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionView;
import io.github.illuseahashmap.workflow.process.application.WorkflowDefinitionService;
import io.github.illuseahashmap.workflow.process.application.WorkflowAdministrationService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDiagramDataView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInstanceDetailView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInstanceSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.TerminateProcessRequest;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final WorkflowAdministrationService administrationService;

    public WorkflowManagementController(WorkflowDefinitionService workflowDefinitionService,
                                        WorkflowAdministrationService administrationService) {
        this.workflowDefinitionService = workflowDefinitionService;
        this.administrationService = administrationService;
    }

    @PostMapping("/deploy")
    @PreAuthorize("hasRole('SERVICE') or hasAuthority('workflow:definition:write')")
    public ApiResponse<DeployProcessResult> deploy(@Valid @RequestBody DeployProcessRequest request) {
        return ApiResponse.ok(workflowDefinitionService.deploy(request));
    }

    @PostMapping("/definition/activate")
    @PreAuthorize("hasRole('SERVICE') or hasAuthority('workflow:definition:write')")
    public ApiResponse<ActiveProcessVersionResult> activate(@Valid @RequestBody ActivateProcessVersionRequest request) {
        return ApiResponse.ok(workflowDefinitionService.activate(request));
    }

    @GetMapping("/definition/active")
    @PreAuthorize("hasRole('SERVICE') or hasAuthority('workflow:definition:read')")
    public ApiResponse<ActiveProcessVersionResult> active(@RequestParam String processDefinitionKey) {
        return ApiResponse.ok(workflowDefinitionService.getActiveVersion(processDefinitionKey));
    }

    @GetMapping("/definition/exists")
    @PreAuthorize("hasRole('SERVICE') or hasAuthority('workflow:definition:read')")
    public ApiResponse<Boolean> exists(@RequestParam String processDefinitionKey) {
        return ApiResponse.ok(workflowDefinitionService.exists(processDefinitionKey));
    }

    @GetMapping("/definitions")
    @PreAuthorize("hasRole('SERVICE') or hasAuthority('workflow:definition:read')")
    public ApiResponse<List<ProcessDefinitionView>> list(
            @RequestParam(required = false) String processDefinitionKey) {
        return ApiResponse.ok(workflowDefinitionService.list(processDefinitionKey));
    }

    @GetMapping("/definition")
    @PreAuthorize("hasRole('SERVICE') or hasAuthority('workflow:definition:read')")
    public ApiResponse<ProcessDefinitionView> detail(@RequestParam String processDefinitionKey,
                                                     @RequestParam(required = false) Integer version) {
        return ApiResponse.ok(workflowDefinitionService.getDefinition(processDefinitionKey, version));
    }

    @GetMapping(value = "/diagram", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize("hasRole('SERVICE') or hasAuthority('workflow:instance:read')")
    public ResponseEntity<byte[]> processDiagram(@RequestParam String processInstanceId) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(administrationService.generateProcessDiagram(processInstanceId));
    }

    @GetMapping("/diagram-data")
    @PreAuthorize("hasRole('SERVICE') or hasAuthority('workflow:instance:read')")
    public ApiResponse<ProcessDiagramDataView> processDiagramData(@RequestParam String processInstanceId) {
        return ApiResponse.ok(administrationService.getProcessDiagramData(processInstanceId));
    }

    @GetMapping("/definitions/page")
    @PreAuthorize("hasRole('SERVICE') or hasAuthority('workflow:definition:read')")
    public ApiResponse<PageResult<ProcessDefinitionSummaryView>> pageDefinitions(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) String processDefinitionName,
            @RequestParam(defaultValue = "all") String publishStatus) {
        return ApiResponse.ok(administrationService.pageProcessDefinitions(
                pageNum, pageSize, processDefinitionKey, processDefinitionName, publishStatus));
    }

    @GetMapping("/instances/page")
    @PreAuthorize("hasRole('SERVICE') or hasAuthority('workflow:instance:read')")
    public ApiResponse<PageResult<ProcessInstanceSummaryView>> pageInstances(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) String processDefinitionName,
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String businessKey,
            @RequestParam(defaultValue = "all") String status) {
        return ApiResponse.ok(administrationService.pageProcessInstances(
                pageNum, pageSize, processDefinitionKey, processDefinitionName,
                processInstanceId, businessKey, status));
    }

    @GetMapping("/instance/detail")
    @PreAuthorize("hasRole('SERVICE') or hasAuthority('workflow:instance:read')")
    public ApiResponse<ProcessInstanceDetailView> instanceDetail(@RequestParam String processInstanceId) {
        return ApiResponse.ok(administrationService.getProcessInstanceDetail(processInstanceId));
    }

    @PostMapping("/instance/terminate")
    @PreAuthorize("hasRole('SERVICE') or hasAuthority('workflow:instance:operate')")
    public ApiResponse<Void> terminate(@Valid @RequestBody TerminateProcessRequest request) {
        administrationService.terminateProcessInstance(request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/definition")
    @PreAuthorize("hasRole('SERVICE') or hasAuthority('workflow:definition:write')")
    public ApiResponse<Void> deleteDefinitions(@RequestParam String processDefinitionKey) {
        administrationService.deleteProcessDefinitions(processDefinitionKey);
        return ApiResponse.ok();
    }

    @DeleteMapping("/definition/version")
    @PreAuthorize("hasRole('SERVICE') or hasAuthority('workflow:definition:write')")
    public ApiResponse<Void> deleteDefinitionVersion(@RequestParam String processDefinitionKey,
                                                     @RequestParam Integer version) {
        administrationService.deleteProcessDefinitionVersion(processDefinitionKey, version);
        return ApiResponse.ok();
    }
}

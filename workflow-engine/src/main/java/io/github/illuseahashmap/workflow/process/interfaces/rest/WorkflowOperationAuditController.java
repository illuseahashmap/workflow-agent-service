package io.github.illuseahashmap.workflow.process.interfaces.rest;

import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditQueryService;
import io.github.illuseahashmap.workflow.process.application.dto.WorkflowOperationAuditView;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/workflow/management/audit")
@PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('workflow:audit:read')")
public class WorkflowOperationAuditController {

    private final WorkflowOperationAuditQueryService service;

    public WorkflowOperationAuditController(WorkflowOperationAuditQueryService service) {
        this.service = service;
    }

    @GetMapping("/operations")
    public ApiResponse<PageResult<WorkflowOperationAuditView>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) Instant occurredFrom,
            @RequestParam(required = false) Instant occurredTo) {
        return ApiResponse.ok(service.page(pageNum, pageSize, eventType, processInstanceId,
                traceId, occurredFrom, occurredTo));
    }
}

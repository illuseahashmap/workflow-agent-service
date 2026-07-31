package io.github.illuseahashmap.workflow.tenant.interfaces.rest;

import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import io.github.illuseahashmap.workflow.tenant.application.TenantManagementService;
import io.github.illuseahashmap.workflow.tenant.application.dto.TenantCommand;
import io.github.illuseahashmap.workflow.tenant.domain.WorkflowTenant;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/workflow/tenant")
public class WorkflowTenantController {

    private final TenantManagementService tenantManagementService;

    public WorkflowTenantController(TenantManagementService tenantManagementService) {
        this.tenantManagementService = tenantManagementService;
    }

    @GetMapping
    public ApiResponse<PageResult<WorkflowTenant>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.ok(tenantManagementService.page(pageNum, pageSize, keyword, enabled));
    }

    @GetMapping("/enabled")
    public ApiResponse<List<WorkflowTenant>> listEnabled() {
        return ApiResponse.ok(tenantManagementService.listEnabled());
    }

    @PostMapping
    public ApiResponse<WorkflowTenant> create(@Valid @RequestBody TenantCommand command) {
        return ApiResponse.ok(tenantManagementService.create(command));
    }

    @PostMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable long id, @Valid @RequestBody TenantCommand command) {
        tenantManagementService.update(id, command);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/enabled")
    public ApiResponse<Void> updateEnabled(@PathVariable long id, @RequestParam boolean enabled) {
        tenantManagementService.updateEnabled(id, enabled);
        return ApiResponse.ok();
    }
}

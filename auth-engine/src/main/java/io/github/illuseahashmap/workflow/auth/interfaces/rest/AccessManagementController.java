package io.github.illuseahashmap.workflow.auth.interfaces.rest;

import io.github.illuseahashmap.workflow.auth.application.AccessManagementService;
import io.github.illuseahashmap.workflow.auth.application.dto.AddTenantMemberRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.PermissionView;
import io.github.illuseahashmap.workflow.auth.application.dto.SaveTenantRoleRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantMemberView;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantRoleView;
import io.github.illuseahashmap.workflow.auth.application.dto.UpdateMemberRolesRequest;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/access")
public class AccessManagementController {

    private final AccessManagementService accessManagementService;

    public AccessManagementController(AccessManagementService accessManagementService) {
        this.accessManagementService = accessManagementService;
    }

    @GetMapping("/members")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('member:manage')")
    public ApiResponse<PageResult<TenantMemberView>> members(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return ApiResponse.ok(accessManagementService.pageMembers(keyword, pageNum, pageSize));
    }

    @PostMapping("/members")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('member:manage')")
    public ApiResponse<TenantMemberView> addMember(@Valid @RequestBody AddTenantMemberRequest request) {
        return ApiResponse.ok(accessManagementService.addMember(request));
    }

    @PostMapping("/members/{userId}/roles")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('member:manage')")
    public ApiResponse<Void> updateMemberRoles(@PathVariable String userId,
                                               @Valid @RequestBody UpdateMemberRolesRequest request) {
        accessManagementService.updateMemberRoles(userId, request.roleCodes());
        return ApiResponse.ok();
    }

    @PostMapping("/members/{userId}/enabled")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('member:manage')")
    public ApiResponse<Void> updateMemberEnabled(@PathVariable String userId,
                                                 @RequestParam boolean enabled) {
        accessManagementService.updateMemberEnabled(userId, enabled);
        return ApiResponse.ok();
    }

    @GetMapping("/roles")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAnyAuthority('member:manage','role:manage')")
    public ApiResponse<PageResult<TenantRoleView>> roles(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return ApiResponse.ok(accessManagementService.pageRoles(pageNum, pageSize));
    }

    @PostMapping("/roles")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('role:manage')")
    public ApiResponse<TenantRoleView> saveRole(@Valid @RequestBody SaveTenantRoleRequest request) {
        return ApiResponse.ok(accessManagementService.saveRole(request));
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('role:manage')")
    public ApiResponse<List<PermissionView>> permissions() {
        return ApiResponse.ok(accessManagementService.permissions());
    }
}

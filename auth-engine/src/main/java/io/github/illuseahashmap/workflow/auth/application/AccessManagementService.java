package io.github.illuseahashmap.workflow.auth.application;

import io.github.illuseahashmap.workflow.auth.application.dto.AddTenantMemberRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.PermissionView;
import io.github.illuseahashmap.workflow.auth.application.dto.SaveTenantRoleRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantMemberView;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantRoleView;
import java.util.List;
import java.util.Set;
import io.github.illuseahashmap.workflow.shared.response.PageResult;

public interface AccessManagementService {

    List<TenantMemberView> members(String keyword);

    PageResult<TenantMemberView> pageMembers(String keyword, Integer pageNum, Integer pageSize);

    TenantMemberView addMember(AddTenantMemberRequest request);

    void updateMemberRoles(String userId, Set<String> roleCodes);

    void updateMemberEnabled(String userId, boolean enabled);

    List<TenantRoleView> roles();

    PageResult<TenantRoleView> pageRoles(Integer pageNum, Integer pageSize);

    TenantRoleView saveRole(SaveTenantRoleRequest request);

    List<PermissionView> permissions();
}

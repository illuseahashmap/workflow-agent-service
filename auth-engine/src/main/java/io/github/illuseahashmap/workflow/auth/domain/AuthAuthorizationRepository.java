package io.github.illuseahashmap.workflow.auth.domain;

import java.util.Set;
import java.util.List;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;

public interface AuthAuthorizationRepository {

    void grantRole(String userId, String tenantCode, String roleCode);

    Set<String> findRoleCodes(String userId, String tenantCode);

    Set<String> findTenantRoleCodes(String userId, String tenantCode);

    Set<String> findPermissionCodes(String userId, String tenantCode);

    List<RoleDefinition> findRoles(String tenantCode);

    PageSlice<RoleDefinition> pageRoles(String tenantCode, int pageNumber, int pageSize);

    List<PermissionDefinition> findPermissions();

    void replaceUserRoles(String userId, String tenantCode, Set<String> roleCodes);

    void saveRole(String tenantCode, String roleCode, String roleName, String description, boolean enabled);

    void replaceRolePermissions(String tenantCode, String roleCode, Set<String> permissionCodes);

    void ensureTenantDefaults(String tenantCode);

    record RoleDefinition(String roleCode, String roleName, String description, boolean enabled,
                          Set<String> permissions) {
    }

    record PermissionDefinition(String permissionCode, String permissionName, String description,
                                PermissionScope scope) {
    }
}

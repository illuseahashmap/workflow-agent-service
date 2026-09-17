package io.github.illuseahashmap.workflow.auth.infrastructure.persistence;

import io.github.illuseahashmap.workflow.auth.domain.AuthAuthorizationRepository;
import io.github.illuseahashmap.workflow.auth.domain.PermissionScope;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthAuthorizationRepository implements AuthAuthorizationRepository {

    private final JdbcClient jdbcClient;

    public JdbcAuthAuthorizationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void grantRole(String userId, String tenantCode, String roleCode) {
        jdbcClient.sql("""
                        INSERT INTO auth_user_role (user_id, tenant_code, role_code)
                        VALUES (:userId, :tenantCode, :roleCode)
                        ON CONFLICT (user_id, tenant_code, role_code) DO NOTHING
                        """)
                .param("userId", userId)
                .param("tenantCode", tenantCode)
                .param("roleCode", roleCode)
                .update();
    }

    @Override
    public Set<String> findRoleCodes(String userId, String tenantCode) {
        return new LinkedHashSet<>(jdbcClient.sql("""
                        SELECT assignment.role_code
                        FROM auth_user_role assignment
                        JOIN auth_role role
                          ON role.tenant_code = assignment.tenant_code
                         AND role.role_code = assignment.role_code
                         AND role.enabled = 1
                        WHERE assignment.user_id = :userId
                          AND assignment.tenant_code IN (:tenantCode, '*')
                        ORDER BY assignment.role_code
                        """)
                .param("userId", userId)
                .param("tenantCode", tenantCode)
                .query(String.class)
                .list());
    }

    @Override
    public Set<String> findTenantRoleCodes(String userId, String tenantCode) {
        return new LinkedHashSet<>(jdbcClient.sql("""
                        SELECT assignment.role_code
                        FROM auth_user_role assignment
                        JOIN auth_role role
                          ON role.tenant_code = assignment.tenant_code
                         AND role.role_code = assignment.role_code
                         AND role.enabled = 1
                        WHERE assignment.user_id = :userId
                          AND assignment.tenant_code = :tenantCode
                        ORDER BY assignment.role_code
                        """)
                .param("userId", userId)
                .param("tenantCode", tenantCode)
                .query(String.class)
                .list());
    }

    @Override
    public Set<String> findPermissionCodes(String userId, String tenantCode) {
        return new LinkedHashSet<>(jdbcClient.sql("""
                        SELECT DISTINCT rp.permission_code
                        FROM auth_user_role ur
                        JOIN auth_role_permission rp
                          ON rp.tenant_code = ur.tenant_code
                         AND rp.role_code = ur.role_code
                        JOIN auth_role role
                          ON role.tenant_code = ur.tenant_code
                         AND role.role_code = ur.role_code
                         AND role.enabled = 1
                        JOIN auth_permission permission
                          ON permission.permission_code = rp.permission_code
                        WHERE ur.user_id = :userId AND ur.tenant_code IN (:tenantCode, '*')
                        ORDER BY rp.permission_code
                        """)
                .param("userId", userId)
                .param("tenantCode", tenantCode)
                .query(String.class)
                .list());
    }

    @Override
    public List<RoleDefinition> findRoles(String tenantCode) {
        List<RoleRow> roles = jdbcClient.sql("""
                        SELECT role_code, role_name, description, enabled
                        FROM auth_role
                        WHERE tenant_code = :tenantCode
                        ORDER BY role_code
                        """)
                .param("tenantCode", tenantCode)
                .query((resultSet, rowNumber) -> new RoleRow(
                        resultSet.getString("role_code"),
                        resultSet.getString("role_name"),
                        resultSet.getString("description"),
                        resultSet.getInt("enabled") == 1))
                .list();
        Map<String, Set<String>> permissionsByRole = new LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT role_code, permission_code
                        FROM auth_role_permission
                        WHERE tenant_code = :tenantCode
                        ORDER BY role_code, permission_code
                        """)
                .param("tenantCode", tenantCode)
                .query((resultSet, rowNumber) -> new RolePermissionRow(
                        resultSet.getString("role_code"), resultSet.getString("permission_code")))
                .list()
                .forEach(row -> permissionsByRole
                        .computeIfAbsent(row.roleCode(), ignored -> new LinkedHashSet<>())
                        .add(row.permissionCode()));
        List<RoleDefinition> definitions = new ArrayList<>(roles.size());
        for (RoleRow role : roles) {
            definitions.add(new RoleDefinition(role.roleCode(), role.roleName(), role.description(), role.enabled(),
                    Set.copyOf(permissionsByRole.getOrDefault(role.roleCode(), Set.of()))));
        }
        return List.copyOf(definitions);
    }

    @Override
    public PageSlice<RoleDefinition> pageRoles(String tenantCode, int pageNumber, int pageSize) {
        Long total = jdbcClient.sql("SELECT COUNT(*) FROM auth_role WHERE tenant_code = :tenantCode")
                .param("tenantCode", tenantCode).query(Long.class).single();
        List<RoleRow> roles = jdbcClient.sql("""
                        SELECT role_code, role_name, description, enabled
                        FROM auth_role
                        WHERE tenant_code = :tenantCode
                        ORDER BY role_code
                        LIMIT :pageSize OFFSET :offset
                        """)
                .param("tenantCode", tenantCode).param("pageSize", pageSize)
                .param("offset", (pageNumber - 1) * pageSize)
                .query((resultSet, rowNumber) -> new RoleRow(
                        resultSet.getString("role_code"), resultSet.getString("role_name"),
                        resultSet.getString("description"), resultSet.getInt("enabled") == 1))
                .list();
        if (roles.isEmpty()) return new PageSlice<>(total == null ? 0 : total, pageNumber, pageSize, List.of());
        List<String> roleCodes = roles.stream().map(RoleRow::roleCode).toList();
        Map<String, Set<String>> permissionsByRole = new LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT role_code, permission_code
                        FROM auth_role_permission
                        WHERE tenant_code = :tenantCode AND role_code IN (:roleCodes)
                        ORDER BY role_code, permission_code
                        """)
                .param("tenantCode", tenantCode).param("roleCodes", roleCodes)
                .query((resultSet, rowNumber) -> new RolePermissionRow(
                        resultSet.getString("role_code"), resultSet.getString("permission_code")))
                .list().forEach(row -> permissionsByRole.computeIfAbsent(row.roleCode(), ignored -> new LinkedHashSet<>())
                        .add(row.permissionCode()));
        List<RoleDefinition> definitions = roles.stream()
                .map(role -> new RoleDefinition(role.roleCode(), role.roleName(), role.description(), role.enabled(),
                        Set.copyOf(permissionsByRole.getOrDefault(role.roleCode(), Set.of()))))
                .toList();
        return new PageSlice<>(total == null ? 0 : total, pageNumber, pageSize, definitions);
    }

    @Override
    public List<PermissionDefinition> findPermissions() {
        return jdbcClient.sql("""
                        SELECT permission_code, permission_name, description, scope
                        FROM auth_permission ORDER BY permission_code
                        """)
                .query((resultSet, rowNumber) -> new PermissionDefinition(
                        resultSet.getString("permission_code"),
                        resultSet.getString("permission_name"),
                        resultSet.getString("description"),
                        PermissionScope.valueOf(resultSet.getString("scope"))))
                .list();
    }

    @Override
    public void replaceUserRoles(String userId, String tenantCode, Set<String> roleCodes) {
        jdbcClient.sql("DELETE FROM auth_user_role WHERE user_id = :userId AND tenant_code = :tenantCode")
                .param("userId", userId)
                .param("tenantCode", tenantCode)
                .update();
        roleCodes.forEach(roleCode -> grantRole(userId, tenantCode, roleCode));
    }

    @Override
    public void saveRole(String tenantCode, String roleCode, String roleName, String description, boolean enabled) {
        jdbcClient.sql("""
                        INSERT INTO auth_role (tenant_code, role_code, role_name, description, enabled)
                        VALUES (:tenantCode, :roleCode, :roleName, :description, :enabled)
                        ON CONFLICT (tenant_code, role_code) DO UPDATE
                        SET role_name = EXCLUDED.role_name, description = EXCLUDED.description,
                            enabled = EXCLUDED.enabled, updated_at = CURRENT_TIMESTAMP
                        """)
                .param("tenantCode", tenantCode)
                .param("roleCode", roleCode)
                .param("roleName", roleName)
                .param("description", description)
                .param("enabled", enabled ? 1 : 0)
                .update();
    }

    @Override
    public void replaceRolePermissions(String tenantCode, String roleCode, Set<String> permissionCodes) {
        jdbcClient.sql("DELETE FROM auth_role_permission WHERE tenant_code = :tenantCode AND role_code = :roleCode")
                .param("tenantCode", tenantCode)
                .param("roleCode", roleCode)
                .update();
        permissionCodes.forEach(permissionCode -> jdbcClient.sql("""
                        INSERT INTO auth_role_permission (tenant_code, role_code, permission_code)
                        VALUES (:tenantCode, :roleCode, :permissionCode)
                        ON CONFLICT (tenant_code, role_code, permission_code) DO NOTHING
                        """)
                .param("tenantCode", tenantCode)
                .param("roleCode", roleCode)
                .param("permissionCode", permissionCode)
                .update());
    }

    @Override
    public void ensureTenantDefaults(String tenantCode) {
        saveRole(tenantCode, "TENANT_ADMIN", "租户管理员", "管理当前租户的成员、角色和工作流", true);
        saveRole(tenantCode, "USER", "普通用户", "查看当前租户的流程定义和流程实例", true);
        replaceRolePermissions(tenantCode, "TENANT_ADMIN", Set.of(
                "workflow:definition:read", "workflow:definition:write",
                "workflow:instance:read", "workflow:instance:operate", "assignment:manage",
                "member:manage", "role:manage", "agent:manage", "agent:run:read", "agent:run:execute",
                "workflow:audit:read"));
        replaceRolePermissions(tenantCode, "USER", Set.of(
                "workflow:definition:read", "workflow:instance:read"));
    }

    private record RoleRow(String roleCode, String roleName, String description, boolean enabled) {
    }

    private record RolePermissionRow(String roleCode, String permissionCode) {
    }
}

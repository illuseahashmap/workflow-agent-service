INSERT INTO auth_role_permission (tenant_code, role_code, permission_code)
SELECT role.tenant_code, role.role_code, permission.permission_code
FROM auth_role role
CROSS JOIN (VALUES ('member:manage'), ('role:manage')) permission(permission_code)
WHERE role.role_code = 'TENANT_ADMIN'
  AND role.tenant_code <> '*'
ON CONFLICT (tenant_code, role_code, permission_code) DO NOTHING;

UPDATE auth_role
SET role_name = '租户管理员',
    description = '管理当前租户的成员、角色和工作流',
    updated_at = CURRENT_TIMESTAMP
WHERE role_code = 'TENANT_ADMIN'
  AND tenant_code <> '*';

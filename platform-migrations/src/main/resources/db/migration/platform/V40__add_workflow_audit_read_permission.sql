INSERT INTO auth_permission (permission_code, permission_name, description, scope)
VALUES ('workflow:audit:read', '查看流程审计', '查看当前租户流程操作审计', 'TENANT')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO auth_role_permission (tenant_code, role_code, permission_code)
SELECT tenant_code, 'TENANT_ADMIN', 'workflow:audit:read'
FROM workflow_tenant
ON CONFLICT (tenant_code, role_code, permission_code) DO NOTHING;

INSERT INTO auth_role_permission (tenant_code, role_code, permission_code)
VALUES ('*', 'PLATFORM_ADMIN', 'workflow:audit:read')
ON CONFLICT (tenant_code, role_code, permission_code) DO NOTHING;

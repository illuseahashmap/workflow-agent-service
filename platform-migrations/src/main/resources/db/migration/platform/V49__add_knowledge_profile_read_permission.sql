INSERT INTO auth_permission (permission_code, permission_name, description, scope)
VALUES ('knowledge:profile:read', '查看知识检索配置', '查看当前租户已发布的检索 Profile', 'TENANT')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO auth_role_permission (tenant_code, role_code, permission_code)
SELECT tenant_code, role_code, 'knowledge:profile:read'
FROM auth_role_permission
WHERE permission_code = 'agent:manage'
ON CONFLICT (tenant_code, role_code, permission_code) DO NOTHING;

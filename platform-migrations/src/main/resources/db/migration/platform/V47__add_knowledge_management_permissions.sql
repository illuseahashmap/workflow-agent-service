INSERT INTO auth_permission (permission_code, permission_name, description, scope)
VALUES
    ('knowledge:profile:manage', '管理知识检索配置', '创建和发布当前租户的检索 Profile', 'TENANT'),
    ('knowledge:document:manage', '管理知识文档', '上传当前租户的文本文档并创建摄取任务', 'TENANT'),
    ('knowledge:ingestion:read', '查看知识摄取任务', '查看当前租户的文档摄取状态', 'TENANT'),
    ('knowledge:scope:manage', '管理知识范围授权', '管理当前租户主体的知识范围授权', 'TENANT')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO auth_role_permission (tenant_code, role_code, permission_code)
SELECT tenant_code, 'TENANT_ADMIN', permission_code
FROM workflow_tenant
CROSS JOIN (VALUES
    ('knowledge:profile:manage'),
    ('knowledge:document:manage'),
    ('knowledge:ingestion:read'),
    ('knowledge:scope:manage')) permissions(permission_code)
ON CONFLICT (tenant_code, role_code, permission_code) DO NOTHING;

INSERT INTO auth_role_permission (tenant_code, role_code, permission_code)
SELECT '*', 'PLATFORM_ADMIN', permission_code
FROM (VALUES
    ('knowledge:profile:manage'),
    ('knowledge:document:manage'),
    ('knowledge:ingestion:read'),
    ('knowledge:scope:manage')) permissions(permission_code)
ON CONFLICT (tenant_code, role_code, permission_code) DO NOTHING;

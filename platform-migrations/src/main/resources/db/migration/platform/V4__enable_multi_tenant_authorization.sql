CREATE TABLE IF NOT EXISTS auth_user_tenant (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    tenant_code VARCHAR(64) NOT NULL,
    enabled SMALLINT NOT NULL DEFAULT 1,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_auth_user_tenant UNIQUE (user_id, tenant_code)
);

CREATE INDEX IF NOT EXISTS idx_auth_user_tenant_tenant
    ON auth_user_tenant (tenant_code, enabled);

INSERT INTO auth_user_tenant (user_id, tenant_code)
SELECT user_id, tenant_code
FROM auth_user
ON CONFLICT (user_id, tenant_code) DO NOTHING;

CREATE TABLE IF NOT EXISTS auth_permission (
    permission_code VARCHAR(128) PRIMARY KEY,
    permission_name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO auth_permission (permission_code, permission_name, description) VALUES
    ('workflow:definition:read', '查看流程定义', '查看当前租户流程定义'),
    ('workflow:definition:write', '管理流程定义', '设计、部署和发布当前租户流程'),
    ('workflow:instance:read', '查看流程实例', '查看当前租户流程实例'),
    ('workflow:instance:operate', '操作流程实例', '发起、转办、审批和终止流程'),
    ('assignment:manage', '管理派单规则', '维护当前租户节点派单规则'),
    ('tenant:manage', '管理租户', '创建、修改、启停平台租户'),
    ('member:manage', '管理成员', '维护当前租户成员及成员角色'),
    ('role:manage', '管理角色', '维护当前租户角色及权限')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO auth_role (tenant_code, role_code, role_name, description)
VALUES ('*', 'PLATFORM_ADMIN', '平台管理员', '管理平台租户和全部租户权限')
ON CONFLICT (tenant_code, role_code) DO NOTHING;

INSERT INTO auth_role (tenant_code, role_code, role_name, description)
SELECT tenant_code, 'TENANT_ADMIN', '租户管理员', '管理当前租户成员、角色和工作流'
FROM workflow_tenant
ON CONFLICT (tenant_code, role_code) DO NOTHING;

INSERT INTO auth_role (tenant_code, role_code, role_name, description)
SELECT tenant_code, 'USER', '普通成员', '使用当前租户工作流功能'
FROM workflow_tenant
ON CONFLICT (tenant_code, role_code) DO NOTHING;

INSERT INTO auth_role_permission (tenant_code, role_code, permission_code)
SELECT '*', 'PLATFORM_ADMIN', permission_code
FROM auth_permission
ON CONFLICT (tenant_code, role_code, permission_code) DO NOTHING;

INSERT INTO auth_role_permission (tenant_code, role_code, permission_code)
SELECT tenant.tenant_code, 'TENANT_ADMIN', permission.permission_code
FROM workflow_tenant tenant
CROSS JOIN auth_permission permission
WHERE permission.permission_code <> 'tenant:manage'
ON CONFLICT (tenant_code, role_code, permission_code) DO NOTHING;

INSERT INTO auth_role_permission (tenant_code, role_code, permission_code)
SELECT tenant.tenant_code, 'USER', permission_code
FROM workflow_tenant tenant
CROSS JOIN (VALUES
    ('workflow:definition:read'),
    ('workflow:instance:read'),
    ('workflow:instance:operate')
) permissions(permission_code)
ON CONFLICT (tenant_code, role_code, permission_code) DO NOTHING;

INSERT INTO auth_user_role (user_id, tenant_code, role_code)
SELECT user_id, tenant_code, 'USER'
FROM auth_user_tenant
ON CONFLICT (user_id, tenant_code, role_code) DO NOTHING;

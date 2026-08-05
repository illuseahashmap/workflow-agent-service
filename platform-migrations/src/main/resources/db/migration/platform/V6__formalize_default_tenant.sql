UPDATE workflow_tenant
SET tenant_name = '默认租户',
    description = '系统初始化的默认租户',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_code = 'default'
  AND (tenant_name = 'Default Tenant' OR description = 'Local development tenant');

DELETE FROM auth_role_permission
WHERE role_code = 'USER'
  AND permission_code NOT IN (
      'workflow:definition:read',
      'workflow:instance:read'
  );

DELETE FROM auth_role_permission
WHERE role_code = 'TENANT_ADMIN'
  AND permission_code NOT IN (
      'workflow:definition:read',
      'workflow:definition:write',
      'workflow:instance:read',
      'workflow:instance:operate',
      'assignment:manage'
  );

UPDATE auth_role
SET role_name = '普通用户',
    description = '查看当前租户的流程定义和流程实例',
    updated_at = CURRENT_TIMESTAMP
WHERE role_code = 'USER';

UPDATE auth_role
SET role_name = '租户管理员',
    description = '管理当前租户的流程和派单规则',
    updated_at = CURRENT_TIMESTAMP
WHERE role_code = 'TENANT_ADMIN';

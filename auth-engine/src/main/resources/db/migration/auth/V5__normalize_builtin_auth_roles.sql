UPDATE auth_role
SET role_name = '普通成员', description = '使用当前租户工作流功能', updated_at = CURRENT_TIMESTAMP
WHERE role_code = 'USER';

DELETE FROM auth_role_permission permission
WHERE permission.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM auth_user_role assignment
      WHERE assignment.tenant_code = permission.tenant_code
        AND assignment.role_code = permission.role_code
  );

DELETE FROM auth_role role
WHERE role.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM auth_user_role assignment
      WHERE assignment.tenant_code = role.tenant_code
        AND assignment.role_code = role.role_code
  );

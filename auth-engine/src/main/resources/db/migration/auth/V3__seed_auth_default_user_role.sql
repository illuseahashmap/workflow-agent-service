INSERT INTO auth_role (tenant_code, role_code, role_name, description)
VALUES ('default', 'USER', 'User', 'Default registered user role')
ON CONFLICT (tenant_code, role_code) DO NOTHING;

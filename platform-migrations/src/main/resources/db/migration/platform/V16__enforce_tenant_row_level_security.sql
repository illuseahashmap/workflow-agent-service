-- Defense in depth for tenant-scoped platform tables.
-- The application sets these transaction/session variables when a pooled
-- connection is borrowed. An unset tenant is intentionally denied access.
DO $$
DECLARE
    table_name TEXT;
    tenant_code_tables CONSTANT TEXT[] := ARRAY[
        'workflow_tenant',
        'auth_user',
        'auth_role',
        'auth_user_role',
        'auth_role_permission',
        'auth_user_tenant',
        'agent_provider',
        'agent_credential',
        'agent_definition',
        'agent_definition_version',
        'agent_run',
        'agent_run_attempt',
        'agent_run_step',
        'agent_run_checkpoint',
        'agent_run_state_history',
        'agent_model_invocation',
        'platform_security_audit'
    ];
    tenant_id_tables CONSTANT TEXT[] := ARRAY[
        'workflow_active_version',
        'workflow_node_assignment_rule',
        'workflow_assignment_target',
        'workflow_node_assignment_rule_condition',
        'workflow_assignment_fallback_command'
    ];
    authentication_tables CONSTANT TEXT[] := ARRAY[
        'workflow_tenant',
        'auth_user',
        'auth_role',
        'auth_user_role',
        'auth_role_permission',
        'auth_user_tenant'
    ];
    authentication_expression TEXT;
BEGIN
    FOREACH table_name IN ARRAY tenant_code_tables LOOP
        authentication_expression := CASE
            WHEN table_name = ANY(authentication_tables)
                THEN ' OR current_setting(''app.authentication'', true) = ''true'' '
            ELSE ''
        END;
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING ('
                || '(current_setting(''app.platform_admin'', true) = ''true'' '
                || 'OR current_setting(''app.system_worker'', true) = ''true'') '
                || authentication_expression
                || 'OR tenant_code = current_setting(''app.tenant_code'', true)) '
                || 'WITH CHECK ('
                || '(current_setting(''app.platform_admin'', true) = ''true'' '
                || 'OR current_setting(''app.system_worker'', true) = ''true'') '
                || authentication_expression
                || 'OR tenant_code = current_setting(''app.tenant_code'', true))',
            'tenant_isolation_' || table_name,
            table_name
        );
    END LOOP;
    FOREACH table_name IN ARRAY tenant_id_tables LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING ('
                || '(current_setting(''app.platform_admin'', true) = ''true'' '
                || 'OR current_setting(''app.system_worker'', true) = ''true'') '
                || 'OR tenant_id = current_setting(''app.tenant_id'', true)) '
                || 'WITH CHECK ('
                || '(current_setting(''app.platform_admin'', true) = ''true'' '
                || 'OR current_setting(''app.system_worker'', true) = ''true'') '
                || 'OR tenant_id = current_setting(''app.tenant_id'', true))',
            'tenant_isolation_' || table_name,
            table_name
        );
    END LOOP;
END $$;

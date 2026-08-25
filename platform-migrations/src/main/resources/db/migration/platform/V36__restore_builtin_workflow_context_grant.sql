-- V35 corrected knowledge_search exposure but accidentally dropped the
-- workflow_process_context grant from the new-tenant trigger. Restore the
-- complete set of safe built-in read-only tools for existing installations.
CREATE OR REPLACE FUNCTION grant_builtin_agent_tools()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    INSERT INTO agent_tool_tenant_grant (tenant_code, tool_code)
    VALUES (NEW.tenant_code, 'agent_run_status')
    ON CONFLICT (tenant_code, tool_code) DO NOTHING;
    INSERT INTO agent_tool_tenant_grant (tenant_code, tool_code)
    VALUES (NEW.tenant_code, 'workflow_process_context')
    ON CONFLICT (tenant_code, tool_code) DO NOTHING;
    RETURN NEW;
END;
$$;

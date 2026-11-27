-- Enable the governed read-only retrieval tool after the retrieval application
-- and tenant-scoped Profile/knowledge authorization are available.
UPDATE agent_tool_definition
SET enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE tool_code = 'knowledge_search';

INSERT INTO agent_tool_tenant_grant (tenant_code, tool_code)
SELECT tenant_code, 'knowledge_search'
FROM workflow_tenant
ON CONFLICT (tenant_code, tool_code) DO UPDATE SET enabled = TRUE;

CREATE OR REPLACE FUNCTION grant_builtin_agent_tools()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    INSERT INTO agent_tool_tenant_grant (tenant_code, tool_code)
    VALUES (NEW.tenant_code, 'agent_run_status'),
           (NEW.tenant_code, 'workflow_process_context'),
           (NEW.tenant_code, 'knowledge_search')
    ON CONFLICT (tenant_code, tool_code) DO UPDATE SET enabled = TRUE;
    RETURN NEW;
END;
$$;

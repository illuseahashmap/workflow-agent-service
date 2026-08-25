-- knowledge_search is a registered capability, but the real Retriever and
-- authorization adapter are not wired yet. Keep it unavailable by default.
UPDATE agent_tool_definition
SET enabled = FALSE,
    updated_at = CURRENT_TIMESTAMP
WHERE tool_code = 'knowledge_search';

DELETE FROM agent_tool_tenant_grant
WHERE tool_code = 'knowledge_search';

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
    RETURN NEW;
END;
$$;

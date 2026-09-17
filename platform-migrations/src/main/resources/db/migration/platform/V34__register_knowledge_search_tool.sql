INSERT INTO agent_tool_definition (tool_code, tool_name, input_schema, read_only)
VALUES (
    'knowledge_search',
    '检索知识库',
    '{"type":"object","required":["query"],"properties":{"query":{"type":"string","minLength":1,"maxLength":2000},"knowledgeScopes":{"type":"array","items":{"type":"string"}},"maxResults":{"type":"integer","minimum":1,"maximum":20}}}',
    TRUE
)
ON CONFLICT (tool_code) DO UPDATE SET input_schema = EXCLUDED.input_schema, enabled = TRUE;

INSERT INTO agent_tool_tenant_grant (tenant_code, tool_code)
SELECT tenant_code, 'knowledge_search' FROM workflow_tenant
ON CONFLICT (tenant_code, tool_code) DO NOTHING;

CREATE OR REPLACE FUNCTION grant_builtin_agent_tools()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    INSERT INTO agent_tool_tenant_grant (tenant_code, tool_code)
    VALUES (NEW.tenant_code, 'agent_run_status'), (NEW.tenant_code, 'knowledge_search')
    ON CONFLICT (tenant_code, tool_code) DO NOTHING;
    RETURN NEW;
END;
$$;

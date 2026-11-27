INSERT INTO agent_tool_definition (tool_code, tool_name, input_schema, read_only)
VALUES (
    'workflow_process_context',
    '读取流程实例业务上下文',
    '{"type":"object","required":["processInstanceId"],"properties":{"processInstanceId":{"type":"string","minLength":1,"maxLength":128}}}',
    TRUE
)
ON CONFLICT (tool_code) DO NOTHING;

INSERT INTO agent_tool_tenant_grant (tenant_code, tool_code)
SELECT tenant_code, 'workflow_process_context' FROM workflow_tenant
ON CONFLICT (tenant_code, tool_code) DO NOTHING;

CREATE OR REPLACE FUNCTION grant_builtin_agent_tools()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    INSERT INTO agent_tool_tenant_grant (tenant_code, tool_code)
    VALUES (NEW.tenant_code, 'agent_run_status'),
           (NEW.tenant_code, 'workflow_process_context')
    ON CONFLICT (tenant_code, tool_code) DO NOTHING;
    RETURN NEW;
END;
$$;

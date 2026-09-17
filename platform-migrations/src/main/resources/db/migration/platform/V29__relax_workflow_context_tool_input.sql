UPDATE agent_tool_definition
SET input_schema = '{"type":"object","properties":{"processInstanceId":{"type":"string","minLength":1,"maxLength":128,"description":"仅手动测试时传入；流程运行由平台上下文注入"}}}',
    updated_at = CURRENT_TIMESTAMP
WHERE tool_code = 'workflow_process_context';

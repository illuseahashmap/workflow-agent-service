ALTER TABLE agent_definition_version
    ADD COLUMN tool_set_json JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Preserve the behavior of already-published PLATFORM_AGENT versions while
-- converting their effective tenant grants into an immutable version snapshot.
UPDATE agent_definition_version version
SET tool_set_json = COALESCE((
    SELECT jsonb_agg(grant_row.tool_code ORDER BY grant_row.tool_code)
    FROM agent_tool_tenant_grant grant_row
    WHERE grant_row.tenant_code = version.tenant_code
), '[]'::jsonb)
WHERE version.execution_mode = 'PLATFORM_AGENT';

ALTER TABLE agent_definition_version
    ADD CONSTRAINT ck_agent_version_tool_set_array
      CHECK (jsonb_typeof(tool_set_json) = 'array');

ALTER TABLE agent_tool_definition
    ADD COLUMN capability_type VARCHAR(32) NOT NULL DEFAULT 'DIRECT',
    ADD COLUMN risk_level VARCHAR(32) NOT NULL DEFAULT 'LOW',
    ADD COLUMN side_effect BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE agent_tool_definition
    ADD CONSTRAINT ck_agent_tool_capability_type
        CHECK (capability_type IN ('DIRECT', 'RAG', 'MCP')),
    ADD CONSTRAINT ck_agent_tool_risk_level
        CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH')),
    ADD CONSTRAINT ck_agent_tool_read_side_effect
        CHECK (NOT (read_only AND side_effect));

UPDATE agent_tool_definition
SET capability_type = 'RAG'
WHERE tool_code = 'knowledge_search';

UPDATE agent_tool_definition
SET capability_type = 'MCP'
WHERE tool_code LIKE 'mcp:%';

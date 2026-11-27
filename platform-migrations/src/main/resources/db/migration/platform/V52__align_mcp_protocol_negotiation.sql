-- LangChain4j 1.20 negotiates legacy Streamable HTTP starting from 2025-11-25.
-- Servers may still select an older compatible version in their initialize response.
ALTER TABLE mcp_connector_version
    ALTER COLUMN protocol_version SET DEFAULT '2025-11-25';

UPDATE mcp_connector_version
SET protocol_version = '2025-11-25'
WHERE protocol_version = '2025-03-26';

ALTER TABLE agent_definition_version
    ADD COLUMN retrieval_profile_version_id BIGINT;

ALTER TABLE agent_definition_version
    ADD CONSTRAINT fk_agent_version_retrieval_profile_tenant
    FOREIGN KEY (retrieval_profile_version_id, tenant_code)
    REFERENCES knowledge_retrieval_profile_version (id, tenant_code);

CREATE INDEX idx_agent_version_retrieval_profile
    ON agent_definition_version (tenant_code, retrieval_profile_version_id);

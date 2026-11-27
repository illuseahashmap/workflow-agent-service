CREATE TABLE knowledge_profile_identity (
    tenant_code VARCHAR(64) NOT NULL,
    profile_code VARCHAR(128) NOT NULL,
    last_version INTEGER NOT NULL CHECK(last_version > 0),
    PRIMARY KEY(tenant_code,profile_code)
);
INSERT INTO knowledge_profile_identity(tenant_code,profile_code,last_version)
SELECT tenant_code,profile_code,MAX(version) FROM knowledge_retrieval_profile_version GROUP BY tenant_code,profile_code;
ALTER TABLE knowledge_profile_identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_profile_identity FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_profile_identity ON knowledge_profile_identity
USING (tenant_code=current_setting('app.tenant_code',true)
    OR current_setting('app.system_worker',true)='true' OR current_setting('app.platform_admin',true)='true')
WITH CHECK (tenant_code=current_setting('app.tenant_code',true)
    OR current_setting('app.system_worker',true)='true' OR current_setting('app.platform_admin',true)='true');

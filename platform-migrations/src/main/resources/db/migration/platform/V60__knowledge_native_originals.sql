-- The immutable source is independent of the parser recipe and its output.
-- Do not fabricate original bytes or source positions for legacy normalized text.
ALTER TABLE knowledge_document_version ADD UNIQUE (tenant_code,source_code,external_document_id,version,id);
CREATE TABLE knowledge_original_file (
    tenant_code varchar(64) NOT NULL,
    source_code varchar(128) NOT NULL,
    external_document_id varchar(512) NOT NULL,
    version integer NOT NULL CHECK(version>0),
    format varchar(12) NOT NULL CHECK(format IN ('MD','XLSX','DOCX','PDF')),
    original_sha256 char(64) NOT NULL,
    original_bytes bytea NOT NULL CHECK(octet_length(original_bytes) BETWEEN 1 AND 2097152),
    PRIMARY KEY (tenant_code,source_code,external_document_id,version),
    FOREIGN KEY(tenant_code,source_code) REFERENCES knowledge_source(tenant_code,source_code)
);
CREATE TABLE knowledge_native_derivation (
    tenant_code varchar(64) NOT NULL,
    source_code varchar(128) NOT NULL,
    external_document_id varchar(512) NOT NULL,
    version integer NOT NULL,
    recipe varchar(64) NOT NULL,
    document_version_id bigint UNIQUE,
    manifest jsonb NOT NULL,
    PRIMARY KEY(tenant_code,source_code,external_document_id,version,recipe),
    FOREIGN KEY(tenant_code,source_code,external_document_id,version)
      REFERENCES knowledge_original_file(tenant_code,source_code,external_document_id,version),
    FOREIGN KEY(tenant_code,source_code,external_document_id,version,document_version_id)
      REFERENCES knowledge_document_version(tenant_code,source_code,external_document_id,version,id),
    CHECK (manifest ? 'blocks' AND jsonb_typeof(manifest->'blocks')='array' AND
           ((document_version_id IS NOT NULL) = (jsonb_array_length(manifest->'blocks')>0)))
);
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['knowledge_original_file','knowledge_native_derivation'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
        EXECUTE format('CREATE POLICY native_tenant ON %I USING (
            current_setting(''app.platform_admin'',true)=''true'' OR current_setting(''app.system_worker'',true)=''true''
            OR tenant_code=current_setting(''app.tenant_code'',true))',t);
        EXECUTE format('CREATE TRIGGER native_immutable BEFORE UPDATE ON %I
            FOR EACH ROW EXECUTE FUNCTION reject_knowledge_document_rewrite()',t);
    END LOOP;
END $$;
CREATE VIEW knowledge_native_original WITH (security_invoker=true) AS
    SELECT tenant_code,source_code,external_document_id,version,f.format,f.original_sha256,f.original_bytes,
           p.document_version_id,p.recipe,p.manifest
    FROM knowledge_original_file f JOIN knowledge_native_derivation p USING(tenant_code,source_code,external_document_id,version);

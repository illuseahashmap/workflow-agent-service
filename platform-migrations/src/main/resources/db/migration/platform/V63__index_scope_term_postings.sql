-- Derived lookup only. The referenced frozen header remains the source of its scope semantics.
CREATE TABLE knowledge_scope_term (
    tenant_code varchar(64) NOT NULL,
    source_code varchar(128) NOT NULL,
    term text COLLATE "C" NOT NULL,
    index_version_id bigint NOT NULL,
    chunk_id varchar(255) NOT NULL,
    PRIMARY KEY (tenant_code, source_code, term, index_version_id, chunk_id),
    FOREIGN KEY (tenant_code, index_version_id, chunk_id)
        REFERENCES knowledge_chunk(tenant_code, index_version_id, chunk_id) ON DELETE CASCADE
);
CREATE INDEX ix_scope_term_header ON knowledge_scope_term(tenant_code,index_version_id,chunk_id);
ALTER TABLE knowledge_scope_term ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_scope_term FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope_term ON knowledge_scope_term USING (
    current_setting('app.platform_admin',true)='true' OR current_setting('app.system_worker',true)='true'
    OR tenant_code=current_setting('app.tenant_code',true)
);

CREATE FUNCTION project_native_scope_terms() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' THEN
        DELETE FROM knowledge_scope_term WHERE tenant_code=OLD.tenant_code
            AND index_version_id=OLD.index_version_id AND chunk_id=OLD.chunk_id;
    END IF;
    INSERT INTO knowledge_scope_term
        SELECT DISTINCT NEW.tenant_code,NEW.source_code,k,NEW.index_version_id,NEW.chunk_id
        FROM unnest(NEW.table_scope_keys) k WHERE k IS NOT NULL;
    RETURN NULL;
END;
$$;
CREATE TRIGGER project_scope_insert AFTER INSERT ON knowledge_chunk
    FOR EACH ROW WHEN (NEW.table_scope_keys IS NOT NULL) EXECUTE FUNCTION project_native_scope_terms();
CREATE TRIGGER project_scope_update AFTER UPDATE OF table_scope_keys,source_code ON knowledge_chunk
    FOR EACH ROW WHEN (OLD.table_scope_keys IS NOT NULL OR NEW.table_scope_keys IS NOT NULL)
    EXECUTE FUNCTION project_native_scope_terms();

-- Include previously published or retired V62 indexes without rewriting evidence or its identity.
SET LOCAL app.system_worker = 'true';
INSERT INTO knowledge_scope_term
    SELECT DISTINCT c.tenant_code,c.source_code,k,c.index_version_id,c.chunk_id
    FROM knowledge_chunk c CROSS JOIN LATERAL unnest(c.table_scope_keys) k
    WHERE c.table_scope_keys IS NOT NULL AND k IS NOT NULL;
ANALYZE knowledge_scope_term;

ALTER TABLE knowledge_index_version ADD CONSTRAINT uk_knowledge_index_source_identity UNIQUE(id,tenant_code,source_code);

CREATE TABLE knowledge_profile_index_binding (
    tenant_code VARCHAR(64) NOT NULL,
    profile_version_id BIGINT NOT NULL,
    source_code VARCHAR(128) NOT NULL,
    index_version_id BIGINT NOT NULL,
    PRIMARY KEY(profile_version_id,source_code),
    FOREIGN KEY(profile_version_id,tenant_code) REFERENCES knowledge_retrieval_profile_version(id,tenant_code),
    FOREIGN KEY(index_version_id,tenant_code,source_code) REFERENCES knowledge_index_version(id,tenant_code,source_code)
);

-- Backfill only the actual historical index, never another source's current ACTIVE pointer.
INSERT INTO knowledge_profile_index_binding(tenant_code,profile_version_id,source_code,index_version_id)
SELECT p.tenant_code,p.id,i.source_code,i.id FROM knowledge_retrieval_profile_version p
JOIN knowledge_index_version i ON i.id=p.index_version_id AND i.tenant_code=p.tenant_code;

ALTER TABLE knowledge_profile_index_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_profile_index_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_knowledge_profile_index_binding ON knowledge_profile_index_binding
USING (current_setting('app.platform_admin',true)='true' OR current_setting('app.system_worker',true)='true'
       OR tenant_code=current_setting('app.tenant_code',true))
WITH CHECK (current_setting('app.platform_admin',true)='true' OR current_setting('app.system_worker',true)='true'
       OR tenant_code=current_setting('app.tenant_code',true));

CREATE FUNCTION protect_published_knowledge_binding() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE target_id BIGINT; target_tenant TEXT;
BEGIN
    target_id := CASE WHEN TG_OP='DELETE' THEN OLD.profile_version_id ELSE NEW.profile_version_id END;
    target_tenant := CASE WHEN TG_OP='DELETE' THEN OLD.tenant_code ELSE NEW.tenant_code END;
    PERFORM 1 FROM knowledge_retrieval_profile_version WHERE id=target_id AND tenant_code=target_tenant FOR UPDATE;
    IF EXISTS(SELECT 1 FROM knowledge_retrieval_profile_version
              WHERE id=target_id AND tenant_code=target_tenant AND status<>'DRAFT') THEN
        RAISE EXCEPTION 'Published knowledge snapshot membership is immutable';
    END IF;
    IF TG_OP='UPDATE' THEN
        RAISE EXCEPTION 'Replace draft membership explicitly; binding identity cannot be updated';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_published_knowledge_binding BEFORE INSERT OR UPDATE OR DELETE ON knowledge_profile_index_binding
FOR EACH ROW EXECUTE FUNCTION protect_published_knowledge_binding();

ALTER TABLE knowledge_retrieval_trace ADD COLUMN index_bindings JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE knowledge_retrieval_profile_version ADD COLUMN score_kind VARCHAR(32);
ALTER TABLE knowledge_retrieval_profile_version ADD COLUMN score_version VARCHAR(128);
UPDATE knowledge_retrieval_profile_version p SET
    score_kind=CASE WHEN p.strategy='VECTOR' THEN 'COSINE_SIMILARITY'
                    WHEN p.strategy='HYBRID' AND i.embedding_model<>'none' THEN 'RRF_NORMALIZED'
                    ELSE 'KEYWORD_RANK' END,
    score_version=CASE WHEN p.strategy='VECTOR' THEN 'cosine-clamped-v1'
                    WHEN p.strategy='HYBRID' AND i.embedding_model<>'none' THEN 'rrf-k60-two-channel-v1'
                    ELSE 'postgres-simple-lexical-v1' END
FROM knowledge_index_version i WHERE i.id=p.index_version_id AND i.tenant_code=p.tenant_code;
ALTER TABLE knowledge_retrieval_profile_version ALTER COLUMN score_kind SET NOT NULL;
ALTER TABLE knowledge_retrieval_profile_version ALTER COLUMN score_version SET NOT NULL;
ALTER TABLE knowledge_retrieval_profile_version ADD CONSTRAINT ck_profile_score_kind
    CHECK(score_kind IN ('KEYWORD_RANK','COSINE_SIMILARITY','RRF_NORMALIZED','RERANK'));

CREATE FUNCTION initialize_knowledge_score_policy() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE model TEXT;
BEGIN
    IF NEW.score_kind IS NULL AND NEW.score_version IS NULL THEN
        SELECT embedding_model INTO model FROM knowledge_index_version
            WHERE id=NEW.index_version_id AND tenant_code=NEW.tenant_code;
        IF NEW.strategy='VECTOR' THEN
            NEW.score_kind := 'COSINE_SIMILARITY'; NEW.score_version := 'cosine-clamped-v1';
        ELSIF NEW.strategy='HYBRID' AND model<>'none' THEN
            NEW.score_kind := 'RRF_NORMALIZED'; NEW.score_version := 'rrf-k60-two-channel-v1';
        ELSE
            NEW.score_kind := 'KEYWORD_RANK'; NEW.score_version := 'postgres-simple-lexical-v1';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER initialize_knowledge_score_policy BEFORE INSERT ON knowledge_retrieval_profile_version
FOR EACH ROW EXECUTE FUNCTION initialize_knowledge_score_policy();

CREATE FUNCTION protect_published_knowledge_profile() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'DRAFT' THEN RAISE EXCEPTION 'New knowledge profiles must start as drafts'; END IF;
        RETURN NEW;
    END IF;
    IF TG_OP='DELETE' THEN
        IF OLD.status<>'DRAFT' THEN RAISE EXCEPTION 'Published knowledge profile cannot be deleted'; END IF;
        RETURN OLD;
    END IF;
    IF OLD.status='DRAFT' AND NEW.status='PUBLISHED' THEN
        IF (NEW.strategy='KEYWORD' AND (NEW.score_kind,NEW.score_version)<>
                ('KEYWORD_RANK','postgres-simple-lexical-v1'))
           OR (NEW.strategy='VECTOR' AND (NEW.score_kind,NEW.score_version)<>
                ('COSINE_SIMILARITY','cosine-clamped-v1'))
           OR (NEW.strategy='HYBRID' AND (NEW.score_kind,NEW.score_version)<>
                ('RRF_NORMALIZED','rrf-k60-two-channel-v1')) THEN
            RAISE EXCEPTION 'Score calibration does not match the retrieval strategy';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM knowledge_profile_index_binding b
                      WHERE b.profile_version_id=NEW.id AND b.tenant_code=NEW.tenant_code AND b.index_version_id=NEW.index_version_id)
           OR EXISTS(SELECT 1 FROM knowledge_retrieval_profile_scope s
                     WHERE s.profile_version_id=NEW.id AND s.tenant_code=NEW.tenant_code
                       AND NOT EXISTS(SELECT 1 FROM knowledge_profile_index_binding b
                           WHERE b.profile_version_id=NEW.id AND b.tenant_code=NEW.tenant_code AND b.source_code=s.scope_code))
           OR EXISTS(SELECT 1 FROM knowledge_profile_index_binding b
                     WHERE b.profile_version_id=NEW.id AND b.tenant_code=NEW.tenant_code
                       AND NOT EXISTS(SELECT 1 FROM knowledge_retrieval_profile_scope s
                           WHERE s.profile_version_id=NEW.id AND s.tenant_code=NEW.tenant_code AND s.scope_code=b.source_code)) THEN
            RAISE EXCEPTION 'Published profile requires complete frozen source membership';
        END IF;
        IF NEW.strategy IN ('VECTOR','HYBRID') AND
           ((SELECT count(DISTINCT i.embedding_model) FROM knowledge_profile_index_binding b
             JOIN knowledge_index_version i ON i.id=b.index_version_id AND i.tenant_code=b.tenant_code
             WHERE b.profile_version_id=NEW.id AND b.tenant_code=NEW.tenant_code)<>1
            OR EXISTS(SELECT 1 FROM knowledge_profile_index_binding b JOIN knowledge_index_version i
                ON i.id=b.index_version_id AND i.tenant_code=b.tenant_code
                WHERE b.profile_version_id=NEW.id AND b.tenant_code=NEW.tenant_code AND i.embedding_model='none')) THEN
            RAISE EXCEPTION 'Vector snapshot members require one compatible embedding recipe';
        END IF;
    END IF;
    IF OLD.status<>'DRAFT' AND NEW.status='DRAFT' THEN
        RAISE EXCEPTION 'Published knowledge profile cannot return to draft';
    END IF;
    IF OLD.status<>'DRAFT' AND
       (NEW.id,NEW.tenant_code,NEW.profile_code,NEW.version,NEW.strategy,NEW.max_results,NEW.minimum_score,
        NEW.index_version_id,NEW.score_kind,NEW.score_version)
       IS DISTINCT FROM
       (OLD.id,OLD.tenant_code,OLD.profile_code,OLD.version,OLD.strategy,OLD.max_results,OLD.minimum_score,
        OLD.index_version_id,OLD.score_kind,OLD.score_version) THEN
        RAISE EXCEPTION 'Published knowledge profile and score policy are immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_published_knowledge_profile BEFORE INSERT OR UPDATE OR DELETE ON knowledge_retrieval_profile_version
FOR EACH ROW EXECUTE FUNCTION protect_published_knowledge_profile();
CREATE TRIGGER guard_published_knowledge_scopes BEFORE INSERT OR UPDATE OR DELETE ON knowledge_retrieval_profile_scope
FOR EACH ROW EXECUTE FUNCTION protect_published_knowledge_binding();

-- The model boundary revalidates a run's full result lineage, never scans an entire tenant ledger.
CREATE INDEX idx_agent_tool_result_recovery ON agent_tool_execution_audit(tenant_code,idempotency_key varchar_pattern_ops)
WHERE status='SUCCEEDED';

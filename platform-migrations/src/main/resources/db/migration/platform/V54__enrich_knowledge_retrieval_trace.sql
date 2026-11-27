ALTER TABLE knowledge_retrieval_trace
    ADD COLUMN profile_version_id BIGINT,
    ADD COLUMN index_version_id BIGINT,
    ADD COLUMN evidence_references JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN elapsed_millis BIGINT NOT NULL DEFAULT 0;

ALTER TABLE knowledge_retrieval_trace
    ADD CONSTRAINT ck_knowledge_retrieval_elapsed CHECK (elapsed_millis >= 0);

CREATE INDEX ix_knowledge_retrieval_trace_tenant_created
    ON knowledge_retrieval_trace (tenant_code, created_at DESC);

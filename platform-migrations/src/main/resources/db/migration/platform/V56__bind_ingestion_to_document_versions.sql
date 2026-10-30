ALTER TABLE knowledge_ingestion_job ADD COLUMN document_version_id BIGINT;
ALTER TABLE knowledge_ingestion_job ADD COLUMN claim_owner VARCHAR(64);

-- Preserve old task IDs and history. Ambiguous historical identity requires operator review.
DO $$ BEGIN
    IF EXISTS (
        SELECT 1 FROM knowledge_ingestion_job j
        WHERE (SELECT count(*) FROM knowledge_document_version d
               WHERE d.tenant_code=j.tenant_code AND d.source_code=j.source_code
                 AND d.content_hash=j.document_hash) <> 1
    ) THEN
        RAISE EXCEPTION 'Ingestion document identity is ambiguous or missing; reconcile legacy jobs before V56';
    END IF;
END $$;
UPDATE knowledge_ingestion_job j SET document_version_id=d.id
FROM knowledge_document_version d
WHERE d.tenant_code=j.tenant_code AND d.source_code=j.source_code AND d.content_hash=j.document_hash;
ALTER TABLE knowledge_ingestion_job ALTER COLUMN document_version_id SET NOT NULL;
ALTER TABLE knowledge_ingestion_job DROP CONSTRAINT uk_knowledge_ingestion_job;
ALTER TABLE knowledge_document_version ADD CONSTRAINT uk_knowledge_document_tenant_id UNIQUE(tenant_code,id);
ALTER TABLE knowledge_ingestion_job ADD CONSTRAINT fk_ingestion_document
    FOREIGN KEY(tenant_code,document_version_id) REFERENCES knowledge_document_version(tenant_code,id);
ALTER TABLE knowledge_ingestion_job ADD CONSTRAINT uk_ingestion_document UNIQUE(tenant_code,document_version_id);
-- Attempts claimed by pre-fencing workers must not complete after deployment.
UPDATE knowledge_ingestion_job SET status='QUEUED', lease_expires_at=NULL, attempt=attempt+1,
    error_code='INGESTION_UPGRADE_RECLAIM' WHERE status='RUNNING';

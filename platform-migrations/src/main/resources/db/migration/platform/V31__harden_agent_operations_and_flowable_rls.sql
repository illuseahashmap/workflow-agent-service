-- Durable audit for operator commands.  State history describes the state
-- transition; this table records the command intent and its requested window.
CREATE TABLE agent_run_operation (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    agent_run_id BIGINT NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    previous_status VARCHAR(16) NOT NULL,
    resulting_status VARCHAR(16) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    retry_window_seconds INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_run_operation_run FOREIGN KEY (agent_run_id, tenant_code)
        REFERENCES agent_run (id, tenant_code) ON DELETE CASCADE,
    CONSTRAINT ck_agent_run_operation_window CHECK (
        retry_window_seconds IS NULL OR retry_window_seconds BETWEEN 30 AND 3600
    )
);

CREATE INDEX idx_agent_run_operation_run
    ON agent_run_operation (tenant_code, agent_run_id, created_at, id);

ALTER TABLE agent_run_operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_run_operation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_agent_run_operation ON agent_run_operation
    USING (
        current_setting('app.platform_admin', true) = 'true'
        OR current_setting('app.system_worker', true) = 'true'
        OR tenant_code = current_setting('app.tenant_code', true)
    )
    WITH CHECK (
        current_setting('app.platform_admin', true) = 'true'
        OR current_setting('app.system_worker', true) = 'true'
        OR tenant_code = current_setting('app.tenant_code', true)
    );

-- Flowable stores its tenant identity in TENANT_ID_.  Apply the same
-- deny-by-default policy to every existing Flowable table that has that
-- column.  Tables without TENANT_ID_ are global engine metadata and are not
-- guessed into a tenant policy.
DO $$
DECLARE
    flowable_table_name TEXT;
BEGIN
    FOR flowable_table_name IN
        SELECT columns.table_name
        FROM information_schema.columns AS columns
        WHERE table_schema = current_schema()
          AND column_name = 'TENANT_ID_'
          AND table_name LIKE 'act_%'
        GROUP BY columns.table_name
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', flowable_table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', flowable_table_name);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I', flowable_table_name, flowable_table_name);
        EXECUTE format(
            'CREATE POLICY tenant_isolation_%I ON %I USING ('
                || '(current_setting(''app.platform_admin'', true) = ''true'' '
                || 'OR current_setting(''app.system_worker'', true) = ''true'' '
                || 'OR tenant_id_ = current_setting(''app.tenant_code'', true)) '
                || 'WITH CHECK ('
                || '(current_setting(''app.platform_admin'', true) = ''true'' '
                || 'OR current_setting(''app.system_worker'', true) = ''true'' '
                || 'OR tenant_id_ = current_setting(''app.tenant_code'', true))',
            flowable_table_name, flowable_table_name
        );
    END LOOP;
END $$;

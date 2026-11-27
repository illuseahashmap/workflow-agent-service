-- Governance only: no graph node positions and no automatic backend switch.
CREATE TABLE agent_graph_capacity_policy (
    id INTEGER PRIMARY KEY CHECK (id=1),
    max_outstanding INTEGER NOT NULL CHECK (max_outstanding>0),
    tenant_outstanding INTEGER NOT NULL CHECK (tenant_outstanding>0),
    max_active INTEGER NOT NULL CHECK (max_active>0),
    tenant_active INTEGER NOT NULL CHECK (tenant_active>0),
    max_calls INTEGER NOT NULL CHECK (max_calls>0),
    tenant_calls INTEGER NOT NULL CHECK (tenant_calls>0),
    dispatch_sequence BIGINT NOT NULL DEFAULT 0
);
INSERT INTO agent_graph_capacity_policy VALUES(1,1000,100,16,4,32,8,0);

CREATE TABLE agent_graph_tenant_turn (
    tenant_code VARCHAR(64) PRIMARY KEY,
    last_dispatch BIGINT NOT NULL DEFAULT 0
);
INSERT INTO agent_graph_tenant_turn(tenant_code) SELECT DISTINCT tenant_code FROM agent_graph_execution;

-- Administratively configured shared account/connector identities, never model-supplied.
CREATE TABLE agent_graph_call_resource (
    resource_key VARCHAR(256) PRIMARY KEY,
    max_calls INTEGER NOT NULL CHECK (max_calls>0)
);
CREATE TABLE agent_graph_call_permit (
    token UUID PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    run_id BIGINT NOT NULL,
    operation_id VARCHAR(256) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE','QUARANTINED','RELEASED')),
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_at TIMESTAMPTZ,
    resolution VARCHAR(128),
    FOREIGN KEY (tenant_code,run_id) REFERENCES agent_graph_execution(tenant_code,run_id)
);
CREATE UNIQUE INDEX graph_call_inflight ON agent_graph_call_permit(tenant_code,run_id,operation_id)
    WHERE state IN ('ACTIVE','QUARANTINED');
CREATE INDEX graph_call_identity ON agent_graph_call_permit(tenant_code,run_id,operation_id);
CREATE INDEX graph_call_counted ON agent_graph_call_permit(tenant_code) WHERE state<>'RELEASED';
CREATE INDEX graph_execution_live_lease ON agent_graph_execution(tenant_code,lease_expires_at) WHERE state='ACTIVE';
CREATE TABLE agent_graph_call_allocation (
    token UUID NOT NULL REFERENCES agent_graph_call_permit(token),
    resource_key VARCHAR(256) NOT NULL REFERENCES agent_graph_call_resource(resource_key),
    PRIMARY KEY(token,resource_key)
);
CREATE INDEX graph_call_allocation_resource ON agent_graph_call_allocation(resource_key,token);

-- Global counts must never run under tenant-filtered visibility. Worker-only tables.
DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['agent_graph_capacity_policy','agent_graph_tenant_turn',
        'agent_graph_call_resource','agent_graph_call_permit','agent_graph_call_allocation'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
        EXECUTE format('CREATE POLICY %I ON %I USING (current_setting(''app.system_worker'',true)=''true'') '
            || 'WITH CHECK (current_setting(''app.system_worker'',true)=''true'')','worker_'||t,t);
    END LOOP;
END $$;

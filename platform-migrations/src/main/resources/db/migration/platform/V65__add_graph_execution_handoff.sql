-- Storage only. No existing Run is migrated or automatically routed to LangGraph.
ALTER TABLE agent_run ADD COLUMN execution_backend VARCHAR(16) NOT NULL DEFAULT 'NATIVE'
    CHECK (execution_backend IN ('NATIVE', 'LANGGRAPH'));

CREATE TABLE agent_graph_execution (
    tenant_code VARCHAR(64) NOT NULL,
    run_id BIGINT NOT NULL,
    binding_json TEXT NOT NULL CHECK (octet_length(binding_json) <= 262144),
    binding_digest VARCHAR(64) NOT NULL,
    input_json TEXT NOT NULL,
    principal_id VARCHAR(128) NOT NULL,
    process_instance_id VARCHAR(128),
    version_tools_json TEXT NOT NULL,
    node_tools_json TEXT,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'REVOKED')),
    generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0),
    capability_hash VARCHAR(64),
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_code, run_id),
    FOREIGN KEY (run_id, tenant_code) REFERENCES agent_run(id, tenant_code),
    CHECK ((capability_hash IS NULL AND lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (capability_hash IS NOT NULL AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL))
);

-- One pending graph invocation per Run, NOT a queue of graph nodes or audit Steps.
CREATE TABLE agent_graph_dispatch (
    tenant_code VARCHAR(64) NOT NULL,
    run_id BIGINT NOT NULL,
    command_id UUID NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('READY', 'RUNNING', 'ACKNOWLEDGED')),
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    checkpoint_id VARCHAR(256),
    wait_id VARCHAR(256),
    observation_json TEXT CHECK (octet_length(observation_json) <= 262144),
    PRIMARY KEY (tenant_code, run_id),
    FOREIGN KEY (tenant_code, run_id) REFERENCES agent_graph_execution(tenant_code, run_id),
    CHECK ((checkpoint_id IS NULL) = (wait_id IS NULL))
);
CREATE INDEX idx_graph_dispatch_ready ON agent_graph_dispatch(tenant_code, available_at, run_id)
    WHERE status IN ('READY', 'RUNNING');

CREATE TABLE agent_graph_reference (
    tenant_code VARCHAR(64) NOT NULL,
    run_id BIGINT NOT NULL,
    reference VARCHAR(256) NOT NULL,
    binding_digest VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('ARGUMENTS', 'TOOL_RECEIPT')),
    operation_id VARCHAR(256) NOT NULL,
    tool_code VARCHAR(255) NOT NULL,
    content_digest VARCHAR(64) NOT NULL,
    body TEXT NOT NULL CHECK (octet_length(body) <= 262144),
    PRIMARY KEY (tenant_code, run_id, reference),
    UNIQUE (tenant_code, run_id, kind, operation_id),
    FOREIGN KEY (tenant_code, run_id) REFERENCES agent_graph_execution(tenant_code, run_id)
);

CREATE FUNCTION reject_graph_reference_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Graph references are immutable' USING ERRCODE='23514';
END $$;
CREATE TRIGGER graph_reference_immutable BEFORE UPDATE OR DELETE ON agent_graph_reference
    FOR EACH ROW EXECUTE FUNCTION reject_graph_reference_rewrite();

CREATE FUNCTION guard_graph_execution_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.tenant_code, NEW.run_id, NEW.binding_json, NEW.binding_digest, NEW.input_json,
           NEW.principal_id, NEW.process_instance_id, NEW.version_tools_json, NEW.node_tools_json)
       IS DISTINCT FROM ROW(OLD.tenant_code, OLD.run_id, OLD.binding_json, OLD.binding_digest, OLD.input_json,
           OLD.principal_id, OLD.process_instance_id, OLD.version_tools_json, OLD.node_tools_json) THEN
        RAISE EXCEPTION 'Graph execution binding is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER graph_execution_binding_immutable BEFORE UPDATE ON agent_graph_execution
    FOR EACH ROW EXECUTE FUNCTION guard_graph_execution_binding();

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['agent_graph_execution','agent_graph_dispatch','agent_graph_reference'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        -- No browser/platform-admin bypass for execution capabilities or raw arguments.
        EXECUTE format('CREATE POLICY %I ON %I USING ('
            || 'tenant_code=current_setting(''app.tenant_code'',true) '
            || 'OR current_setting(''app.system_worker'',true)=''true'') WITH CHECK ('
            || 'tenant_code=current_setting(''app.tenant_code'',true) '
            || 'OR current_setting(''app.system_worker'',true)=''true'')', 'isolate_' || t, t);
    END LOOP;
END $$;

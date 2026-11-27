-- All Run backends, including terminal retries. AFTER triggers do not charge ON CONFLICT no-ops.
-- Hold writers from the baseline count until the trigger exists (transactional Flyway migration).
LOCK TABLE agent_run IN SHARE ROW EXCLUSIVE MODE;
-- Flyway uses its own migration connection, not the application's tenant-aware DataSource.
-- Scope elevation to this trusted DDL transaction so FORCE RLS cannot hide the baseline.
SELECT set_config('app.system_worker','true',true);
CREATE TABLE agent_run_admission (
    scope VARCHAR(80) PRIMARY KEY,
    used BIGINT NOT NULL CHECK(used>=0),
    hard_limit BIGINT NOT NULL CHECK(hard_limit>0),
    tenant_limit BIGINT NOT NULL CHECK(tenant_limit>0)
);
INSERT INTO agent_run_admission
SELECT 'GLOBAL',count(*),1000,100 FROM agent_run WHERE status IN ('QUEUED','RUNNING','PAUSED');
INSERT INTO agent_run_admission
SELECT 'TENANT:'||tenant_code,count(*),100,100 FROM agent_run WHERE status IN ('QUEUED','RUNNING','PAUSED') GROUP BY tenant_code;
REVOKE ALL ON agent_run_admission FROM PUBLIC;
ALTER TABLE agent_run_admission ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_run_admission FORCE ROW LEVEL SECURITY;
DO $$ BEGIN
    EXECUTE format('CREATE POLICY admission_owner ON agent_run_admission USING (current_user=%L OR current_setting(''app.system_worker'',true)=''true'') '
        || 'WITH CHECK (current_user=%L OR current_setting(''app.system_worker'',true)=''true'')',current_user,current_user);
END $$;

CREATE FUNCTION enforce_agent_run_admission() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE delta INTEGER; old_active BOOLEAN := false; new_active BOOLEAN := false;
    tenant TEXT; global_used BIGINT; global_limit BIGINT; default_tenant_limit BIGINT;
BEGIN
    IF TG_OP<>'INSERT' THEN old_active := OLD.status IN ('QUEUED','RUNNING','PAUSED'); END IF;
    IF TG_OP<>'DELETE' THEN new_active := NEW.status IN ('QUEUED','RUNNING','PAUSED'); END IF;
    IF TG_OP='UPDATE' AND NEW.tenant_code IS DISTINCT FROM OLD.tenant_code THEN
        RAISE EXCEPTION 'Run tenant is immutable' USING ERRCODE='23514';
    END IF;
    delta := new_active::INTEGER-old_active::INTEGER;
    IF delta=0 THEN RETURN NULL; END IF;
    IF TG_OP='DELETE' THEN tenant := OLD.tenant_code; ELSE tenant := NEW.tenant_code; END IF;
    -- Schema-qualified identifiers originate from the trigger, not a supplied search_path.
    EXECUTE format('SELECT used,hard_limit,tenant_limit FROM %I.agent_run_admission WHERE scope=''GLOBAL'' FOR UPDATE',TG_TABLE_SCHEMA)
        INTO global_used,global_limit,default_tenant_limit;
    IF global_used IS NULL THEN RAISE EXCEPTION 'Admission policy missing' USING ERRCODE='PWA02'; END IF;
    IF delta>0 AND global_used>=global_limit THEN RAISE EXCEPTION 'Agent capacity exhausted' USING ERRCODE='PWA01'; END IF;
    EXECUTE format('INSERT INTO %I.agent_run_admission VALUES($1,0,$2,$2) ON CONFLICT DO NOTHING',TG_TABLE_SCHEMA)
        USING 'TENANT:'||tenant,default_tenant_limit;
    EXECUTE format('UPDATE %I.agent_run_admission SET used=used+$1 WHERE scope=$2 AND ($1<0 OR used<hard_limit)',TG_TABLE_SCHEMA)
        USING delta,'TENANT:'||tenant;
    GET DIAGNOSTICS global_used=ROW_COUNT;
    IF global_used<>1 THEN RAISE EXCEPTION 'Agent capacity exhausted' USING ERRCODE='PWA01'; END IF;
    EXECUTE format('UPDATE %I.agent_run_admission SET used=used+$1 WHERE scope=''GLOBAL''',TG_TABLE_SCHEMA) USING delta;
    RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION enforce_agent_run_admission() FROM PUBLIC;
CREATE TRIGGER agent_run_admission AFTER INSERT OR UPDATE OF status,tenant_code OR DELETE ON agent_run
    FOR EACH ROW EXECUTE FUNCTION enforce_agent_run_admission();

-- Trusted deployment/admin mapping. Several logical providers may share ONE physical account key.
CREATE TABLE agent_graph_resource_binding (
    tenant_code VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK(kind IN ('PROVIDER','CONNECTOR','BUILTIN')),
    target_id VARCHAR(256) NOT NULL,
    resource_key VARCHAR(256) NOT NULL REFERENCES agent_graph_call_resource(resource_key),
    PRIMARY KEY(tenant_code,kind,target_id)
);
ALTER TABLE agent_graph_resource_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_graph_resource_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY graph_resource_worker ON agent_graph_resource_binding
    USING(current_setting('app.system_worker',true)='true') WITH CHECK(current_setting('app.system_worker',true)='true');

CREATE TABLE agent_graph_resource_audit (
    revision BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor VARCHAR(256) NOT NULL,
    patch_json JSONB NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE agent_graph_resource_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_graph_resource_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY graph_resource_audit_worker ON agent_graph_resource_audit
    USING(current_setting('app.system_worker',true)='true') WITH CHECK(current_setting('app.system_worker',true)='true');
CREATE FUNCTION reject_graph_resource_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Resource audit is append-only' USING ERRCODE='23514'; END $$;
CREATE TRIGGER graph_resource_audit_immutable BEFORE UPDATE OR DELETE ON agent_graph_resource_audit
    FOR EACH ROW EXECUTE FUNCTION reject_graph_resource_audit_mutation();

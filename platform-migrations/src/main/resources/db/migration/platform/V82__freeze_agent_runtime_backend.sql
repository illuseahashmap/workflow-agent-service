CREATE TABLE agent_version_runtime (
 tenant_code VARCHAR(64) NOT NULL,
 agent_version_id BIGINT NOT NULL,
 backend VARCHAR(16) NOT NULL CHECK (backend IN ('NATIVE','LANGGRAPH')),
 artifact_digest VARCHAR(64),
 state_schema_version INTEGER NOT NULL DEFAULT 2 CHECK (state_schema_version=2),
 max_model_calls INTEGER NOT NULL CHECK (max_model_calls BETWEEN 1 AND 100),
 max_tool_calls INTEGER NOT NULL CHECK (max_tool_calls BETWEEN 0 AND 200),
 PRIMARY KEY(tenant_code,agent_version_id),
 FOREIGN KEY(agent_version_id,tenant_code) REFERENCES agent_definition_version(id,tenant_code) ON DELETE CASCADE,
 CHECK ((backend='NATIVE' AND artifact_digest IS NULL) OR (backend='LANGGRAPH' AND artifact_digest IS NOT NULL AND artifact_digest ~ '^[a-f0-9]{64}$'))
);
CREATE TABLE agent_version_runtime_audit (
 id BIGSERIAL PRIMARY KEY, tenant_code VARCHAR(64) NOT NULL, agent_version_id BIGINT NOT NULL,
 actor VARCHAR(128) NOT NULL, configuration JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE FUNCTION guard_agent_runtime_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE version_status VARCHAR; mode VARCHAR;
BEGIN
 SELECT status,execution_mode INTO version_status,mode FROM agent_definition_version
 WHERE tenant_code=coalesce(NEW.tenant_code,OLD.tenant_code) AND id=coalesce(NEW.agent_version_id,OLD.agent_version_id) FOR UPDATE;
 IF version_status IS NOT NULL AND version_status<>'DRAFT' THEN
   RAISE EXCEPTION 'Published runtime binding is immutable' USING ERRCODE='23514';
 END IF;
 IF TG_OP<>'DELETE' AND (version_status IS NULL OR (NEW.backend='LANGGRAPH' AND mode<>'PLATFORM_AGENT')) THEN
   RAISE EXCEPTION 'Graph runtime requires a draft platform agent' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND (NEW.tenant_code,NEW.agent_version_id) IS DISTINCT FROM (OLD.tenant_code,OLD.agent_version_id) THEN
   RAISE EXCEPTION 'Runtime binding identity is immutable' USING ERRCODE='23514';
 END IF;
 RETURN coalesce(NEW,OLD);
END $$;
CREATE TRIGGER agent_runtime_binding_guard BEFORE INSERT OR UPDATE OR DELETE ON agent_version_runtime
 FOR EACH ROW EXECUTE FUNCTION guard_agent_runtime_binding();
CREATE TRIGGER agent_runtime_audit_immutable BEFORE UPDATE OR DELETE ON agent_version_runtime_audit
 FOR EACH ROW EXECUTE FUNCTION reject_agent_tool_snapshot_rewrite();
ALTER TABLE agent_version_runtime ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_version_runtime FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_version_runtime_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_version_runtime_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_tenant ON agent_version_runtime USING
 (tenant_code=current_setting('app.tenant_code',true) OR current_setting('app.system_worker',true)='true' OR current_setting('app.platform_admin',true)='true')
 WITH CHECK (tenant_code=current_setting('app.tenant_code',true) OR current_setting('app.system_worker',true)='true' OR current_setting('app.platform_admin',true)='true');
CREATE POLICY agent_runtime_audit_tenant ON agent_version_runtime_audit USING
 (tenant_code=current_setting('app.tenant_code',true) OR current_setting('app.system_worker',true)='true' OR current_setting('app.platform_admin',true)='true')
 WITH CHECK (tenant_code=current_setting('app.tenant_code',true) OR current_setting('app.system_worker',true)='true' OR current_setting('app.platform_admin',true)='true');

CREATE FUNCTION inherit_agent_runtime_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='DRAFT' THEN
  INSERT INTO agent_version_runtime
  SELECT NEW.tenant_code,NEW.id,r.backend,r.artifact_digest,r.state_schema_version,r.max_model_calls,r.max_tool_calls
  FROM agent_definition_version v JOIN agent_version_runtime r ON r.agent_version_id=v.id AND r.tenant_code=v.tenant_code
  WHERE v.tenant_code=NEW.tenant_code AND v.definition_id=NEW.definition_id AND v.version=NEW.version-1;
  INSERT INTO agent_version_runtime_audit(tenant_code,agent_version_id,actor,configuration)
  SELECT NEW.tenant_code,NEW.id,NEW.created_by,to_jsonb(r) FROM agent_version_runtime r
  WHERE r.tenant_code=NEW.tenant_code AND r.agent_version_id=NEW.id;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER inherit_agent_runtime AFTER INSERT ON agent_definition_version FOR EACH ROW EXECUTE FUNCTION inherit_agent_runtime_binding();

CREATE FUNCTION guard_agent_runtime_mode() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.execution_mode<>'PLATFORM_AGENT' AND EXISTS(SELECT 1 FROM agent_version_runtime WHERE tenant_code=NEW.tenant_code AND agent_version_id=NEW.id AND backend='LANGGRAPH') THEN
  RAISE EXCEPTION 'Select native runtime before changing execution mode' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_agent_runtime_mode BEFORE UPDATE ON agent_definition_version FOR EACH ROW EXECUTE FUNCTION guard_agent_runtime_mode();

-- Opt-in frozen route policy; historical runs retain their original behavior.
-- A correction belongs to the Run, not exclusively to a CHECKLIST.
ALTER TABLE agent_graph_correction DROP CONSTRAINT agent_graph_correction_tenant_code_run_id_fkey;
ALTER TABLE agent_graph_correction ADD FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_run_budget(tenant_code,run_id);
ALTER TABLE agent_graph_correction DROP CONSTRAINT agent_graph_correction_reason_check;
ALTER TABLE agent_graph_correction ADD CHECK(reason IN ('LOOP_DETECTED','FORMAT_INVALID','ROUTE_INVALID'));
CREATE TABLE agent_graph_route_policy (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, policy_json TEXT NOT NULL,
 PRIMARY KEY(tenant_code,run_id),
 FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_run_budget(tenant_code,run_id)
);
CREATE TABLE agent_graph_route_verdict (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, operation_id TEXT NOT NULL,
 candidate_ref TEXT NOT NULL, accepted BOOLEAN NOT NULL, route_json TEXT,
 PRIMARY KEY(tenant_code,run_id,operation_id),
 FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_route_policy(tenant_code,run_id),
 FOREIGN KEY(tenant_code,run_id,operation_id) REFERENCES agent_graph_v2_validation(tenant_code,run_id,operation_id),
 CHECK(NOT accepted OR route_json IS NOT NULL)
);
CREATE UNIQUE INDEX agent_graph_route_one_accepted ON agent_graph_route_verdict(tenant_code,run_id) WHERE accepted;
CREATE TRIGGER agent_graph_route_policy_immutable BEFORE UPDATE OR DELETE ON agent_graph_route_policy
 FOR EACH ROW EXECUTE FUNCTION reject_graph_reference_rewrite();
CREATE TRIGGER agent_graph_route_verdict_immutable BEFORE UPDATE OR DELETE ON agent_graph_route_verdict
 FOR EACH ROW EXECUTE FUNCTION reject_graph_reference_rewrite();
ALTER TABLE agent_graph_route_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_graph_route_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY isolate_graph_route_policy ON agent_graph_route_policy
 USING (tenant_code=current_setting('app.tenant_code',true) OR current_setting('app.system_worker',true)='true')
 WITH CHECK (tenant_code=current_setting('app.tenant_code',true) OR current_setting('app.system_worker',true)='true');
ALTER TABLE agent_graph_route_verdict ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_graph_route_verdict FORCE ROW LEVEL SECURITY;
CREATE POLICY isolate_graph_route_verdict ON agent_graph_route_verdict
 USING (tenant_code=current_setting('app.tenant_code',true) OR current_setting('app.system_worker',true)='true')
 WITH CHECK (tenant_code=current_setting('app.tenant_code',true) OR current_setting('app.system_worker',true)='true');

-- Preserve the old CHECKLIST prerequisite while allowing separately frozen routing permission.
CREATE FUNCTION require_graph_correction_policy() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.reason='ROUTE_INVALID' THEN
  IF NOT EXISTS(SELECT 1 FROM agent_graph_route_policy p WHERE p.tenant_code=NEW.tenant_code AND p.run_id=NEW.run_id
    AND (p.policy_json::jsonb->>'allow_correction')::boolean) THEN
   RAISE EXCEPTION 'Route correction requires frozen permission';
  END IF;
 ELSIF NOT EXISTS(SELECT 1 FROM agent_graph_task_contract t WHERE t.tenant_code=NEW.tenant_code AND t.run_id=NEW.run_id) THEN
  RAISE EXCEPTION 'Checklist correction requires frozen task contract';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER agent_graph_correction_policy BEFORE INSERT ON agent_graph_correction
 FOR EACH ROW EXECUTE FUNCTION require_graph_correction_policy();

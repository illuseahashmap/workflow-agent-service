-- Existing runs do not opt in; format repair shares the immutable per-Run correction grant.
ALTER TABLE agent_graph_correction DROP CONSTRAINT agent_graph_correction_reason_check;
ALTER TABLE agent_graph_correction ADD CHECK(reason IN ('LOOP_DETECTED','FORMAT_INVALID'));
CREATE TABLE agent_graph_format_policy (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, policy_json TEXT NOT NULL,
 PRIMARY KEY(tenant_code,run_id),
 FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_task_contract(tenant_code,run_id)
);
CREATE TRIGGER agent_graph_format_policy_immutable BEFORE UPDATE OR DELETE ON agent_graph_format_policy
 FOR EACH ROW EXECUTE FUNCTION reject_graph_reference_rewrite();
ALTER TABLE agent_graph_format_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_graph_format_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY isolate_agent_graph_format_policy ON agent_graph_format_policy
 USING (tenant_code=current_setting('app.tenant_code',true) OR current_setting('app.system_worker',true)='true')
 WITH CHECK (tenant_code=current_setting('app.tenant_code',true) OR current_setting('app.system_worker',true)='true');

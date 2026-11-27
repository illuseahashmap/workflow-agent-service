-- Editable policy, independent of immutable Run bindings. Existing limits remain defaults.
ALTER TABLE agent_graph_capacity_policy ADD COLUMN configuration_revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE agent_graph_tenant_capacity (
 tenant_code VARCHAR(64) PRIMARY KEY REFERENCES workflow_tenant(tenant_code),
 outstanding INTEGER CHECK(outstanding>0 AND outstanding<=1000000),
 active INTEGER CHECK(active>0 AND active<=1000000),
 calls INTEGER CHECK(calls>0 AND calls<=1000000)
);
INSERT INTO agent_graph_tenant_capacity(tenant_code,outstanding)
SELECT t.tenant_code,a.hard_limit::INTEGER FROM workflow_tenant t
JOIN agent_run_admission a ON a.scope='TENANT:'||t.tenant_code
JOIN agent_run_admission g ON g.scope='GLOBAL'
WHERE a.hard_limit<>g.tenant_limit AND a.hard_limit<=1000000;
ALTER TABLE agent_graph_tenant_capacity ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_graph_tenant_capacity FORCE ROW LEVEL SECURITY;
CREATE POLICY worker_agent_graph_tenant_capacity ON agent_graph_tenant_capacity
 USING(current_setting('app.system_worker',true)='true')
 WITH CHECK(current_setting('app.system_worker',true)='true');

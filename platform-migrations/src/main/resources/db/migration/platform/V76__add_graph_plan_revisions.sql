-- Preserve old verdicts and their intent FKs. New revisions append; no historical policy rewrite.
ALTER TABLE agent_graph_plan_verdict DROP CONSTRAINT agent_graph_plan_verdict_pkey;
ALTER TABLE agent_graph_plan_verdict ADD PRIMARY KEY(tenant_code,run_id,operation_id);
CREATE UNIQUE INDEX graph_plan_accepted_revision ON agent_graph_plan_verdict
 (tenant_code,run_id,((plan_json::jsonb->>'revision')::integer)) WHERE accepted;

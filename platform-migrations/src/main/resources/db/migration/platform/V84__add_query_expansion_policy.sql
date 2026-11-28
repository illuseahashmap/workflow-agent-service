-- Defaults preserve published single-query behavior; opt-in requires a new profile version.
ALTER TABLE knowledge_retrieval_profile_version
    ADD COLUMN query_expansion_policy VARCHAR(32) NOT NULL DEFAULT 'DISABLED'
        CHECK (query_expansion_policy IN ('DISABLED', 'RRF_V1'));
ALTER TABLE knowledge_retrieval_trace
    ADD COLUMN query_expansion JSONB NOT NULL DEFAULT '{}';
-- Update the catalogue only. Published AgentVersion tool snapshots remain immutable.
UPDATE agent_tool_definition
SET input_schema = jsonb_set(input_schema::jsonb, '{properties,auxiliaryQueries}',
    '{"type":"array","maxItems":1,"items":{"type":"string","minLength":1,"maxLength":2000},"description":"Optional complementary search wording when a concrete question may not match policy wording. Keep query faithful to the user; preserve entities and conditions, never invent thresholds or answers. At most one query; only used by an opted-in retrieval profile."}'::jsonb)::text
WHERE tool_code = 'knowledge_search';

-- Keep expansion policy frozen just like the existing score/index contract.
CREATE FUNCTION protect_published_query_expansion() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'DRAFT' AND NEW.query_expansion_policy IS DISTINCT FROM OLD.query_expansion_policy THEN
        RAISE EXCEPTION 'Published query expansion policy is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_published_query_expansion BEFORE UPDATE ON knowledge_retrieval_profile_version
FOR EACH ROW EXECUTE FUNCTION protect_published_query_expansion();

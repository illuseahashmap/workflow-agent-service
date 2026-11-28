-- New publications receive the topic-only guidance. Never rewrite published snapshots.
UPDATE agent_tool_definition
SET input_schema = jsonb_set(input_schema::jsonb, '{properties,auxiliaryQueries,description}',
    '"Optional topic keywords only: use policy/category vocabulary without concrete numbers, dates, ranges, comparisons, negations or eligibility rules. Preserve all original facts and conditions in query. Never infer thresholds or answers. Omit auxiliaryQueries if unsure."'::jsonb)::text
WHERE tool_code = 'knowledge_search';

UPDATE agent_tool_definition
SET input_schema = jsonb_set(input_schema::jsonb, '{properties,query,description}',
    '"Main search question with original factual constraints. Never append instructions, explanations or hypothetical conditions."'::jsonb)::text
WHERE tool_code = 'knowledge_search';

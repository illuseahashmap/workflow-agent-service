-- Offline, one-time bridge ONLY for main's released query-expansion V81/V82.
-- Stop all API/worker/migration processes and take a database backup first.
-- Run with psql -v ON_ERROR_STOP=1; then migrate ONCE with outOfOrder=true.
-- Never run flyway repair. Checksums and installed_rank are preserved.
BEGIN;
LOCK TABLE public.flyway_schema_history IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version='81' AND success
        AND type='SQL' AND script='V81__add_query_expansion_policy.sql'
        AND description='add query expansion policy' AND checksum=-778765555)
       OR (SELECT count(*) FROM public.flyway_schema_history
        WHERE version IN ('81','82') AND success AND type='SQL' AND
        ((version='81' AND script='V81__add_query_expansion_policy.sql'
          AND description='add query expansion policy' AND checksum=-778765555)
         OR (version='82' AND script='V82__clarify_auxiliary_topic_query.sql'
          AND description='clarify auxiliary topic query' AND checksum=-1502553154))) <>
        (SELECT count(*) FROM public.flyway_schema_history WHERE version IN ('81','82'))
       OR EXISTS (SELECT 1 FROM public.flyway_schema_history
                  WHERE NOT success OR version::text IN ('83','84','85'))
       OR EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version ~ '^[0-9]+$' AND version::integer > 85)
       OR EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version IN ('81','82') GROUP BY version HAVING count(*)<>1)
    THEN
        RAISE EXCEPTION 'Not the exact supported main V82 history; no changes made';
    END IF;
END $$;
CREATE TABLE public.workflow_migration_bridge_audit (
    bridge_id text PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    applied_by text NOT NULL DEFAULT current_user,
    history_before jsonb NOT NULL
);
INSERT INTO public.workflow_migration_bridge_audit(bridge_id,history_before)
SELECT 'main-query-v82-to-canonical-v85', jsonb_agg(to_jsonb(h) ORDER BY installed_rank)
FROM public.flyway_schema_history h;
UPDATE public.flyway_schema_history SET version='84', script='V84__add_query_expansion_policy.sql'
WHERE version='81';
UPDATE public.flyway_schema_history SET version='85', script='V85__clarify_auxiliary_topic_query.sql'
WHERE version='82';
COMMIT;

-- V31 originally matched the Flowable tenant column using the upper-case
-- spelling. PostgreSQL folds unquoted identifiers to lower case, so existing
-- Flowable tables with tenant_id_ were not protected. Reconcile all existing
-- Flowable tables without changing the already-published V31 checksum.
DO $$
DECLARE
    flowable_table_name TEXT;
BEGIN
    FOR flowable_table_name IN
        SELECT columns.table_name
        FROM information_schema.columns AS columns
        WHERE columns.table_schema = current_schema()
          AND LOWER(columns.column_name) = 'tenant_id_'
          AND columns.table_name LIKE 'act_%'
        GROUP BY columns.table_name
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', flowable_table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', flowable_table_name);
        EXECUTE format(
            'DROP POLICY IF EXISTS tenant_isolation_%I ON %I',
            flowable_table_name, flowable_table_name
        );
        EXECUTE format(
            'CREATE POLICY tenant_isolation_%I ON %I USING ('
                || '(current_setting(''app.platform_admin'', true) = ''true'' '
                || 'OR current_setting(''app.system_worker'', true) = ''true'' '
                || 'OR tenant_id_ = current_setting(''app.tenant_code'', true))) '
                || 'WITH CHECK ('
                || '(current_setting(''app.platform_admin'', true) = ''true'' '
                || 'OR current_setting(''app.system_worker'', true) = ''true'' '
                || 'OR tenant_id_ = current_setting(''app.tenant_code'', true)))',
            flowable_table_name, flowable_table_name
        );
    END LOOP;
END $$;

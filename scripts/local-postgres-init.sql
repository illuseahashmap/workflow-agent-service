-- LOCAL DEVELOPMENT ONLY. No application tables are created here; Flyway owns them.
-- Run as a PostgreSQL administrator, for example:
--   psql -h localhost -p 5432 -U postgres -d postgres -f scripts/local-postgres-init.sql

DO
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'workflow_agent') THEN
        CREATE ROLE workflow_agent LOGIN PASSWORD 'workflow_agent';
        -- Existing credentials must never be reset by a repeated initialization.
    END IF;
END
$$;

SELECT 'CREATE DATABASE workflow_agent OWNER workflow_agent'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'workflow_agent')
\gexec

-- Existing databases/owners/credentials are deliberately left unchanged.
-- The newly created database is already owned by workflow_agent.

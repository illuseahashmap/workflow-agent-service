-- Run as a PostgreSQL administrator, for example:
--   psql -h localhost -p 5432 -U postgres -d postgres -f scripts/local-postgres-init.sql

DO
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'workflow_agent') THEN
        CREATE ROLE workflow_agent LOGIN PASSWORD 'workflow_agent';
    ELSE
        ALTER ROLE workflow_agent WITH LOGIN PASSWORD 'workflow_agent';
    END IF;
END
$$;

SELECT 'CREATE DATABASE workflow_agent OWNER workflow_agent'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'workflow_agent')
\gexec

GRANT ALL PRIVILEGES ON DATABASE workflow_agent TO workflow_agent;

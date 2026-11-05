-- One-time local setup: a dedicated role and schema inside the existing "postgres" database.
-- Run as the postgres superuser:
--   psql -h localhost -U postgres -d postgres -f scripts/db/create-schema.sql
-- Put the same password in .env (PAGELENS_DB_PASSWORD).
-- Safe to re-run. Flyway creates all tables in the schema on first app start.

SELECT 'CREATE ROLE pagelens WITH LOGIN PASSWORD ''myjava123'''
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pagelens')
\gexec

CREATE SCHEMA IF NOT EXISTS pagelens AUTHORIZATION pagelens;

-- So ad-hoc psql sessions as pagelens see the PageLens tables without a schema prefix.
ALTER ROLE pagelens SET search_path = pagelens;

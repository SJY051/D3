#!/bin/sh
set -eu

service_password="${D3_SERVICE_DB_PASSWORD:-local-only}"

for service in identity battle judge community; do
  database="d3_${service}"
  role="d3_${service}"

  psql --set=ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set=database="$database" \
    --set=role="$role" \
    --set=password="$service_password" <<-'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'role', :'password')
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = :'role') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'database', :'role')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'database') \gexec
SQL
done

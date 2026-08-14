#!/bin/sh
set -eu

service_password="${D3_SERVICE_DB_PASSWORD:-local-only}"
identity_role="${IDENTITY_DB_USER:-d3_identity}"
battle_role="${BATTLE_DB_USER:-d3_battle}"
judge_role="${JUDGE_DB_USER:-d3_judge}"
community_role="${COMMUNITY_DB_USER:-d3_community}"

if [ "$identity_role" = "$POSTGRES_USER" ] \
  || [ "$battle_role" = "$POSTGRES_USER" ] \
  || [ "$judge_role" = "$POSTGRES_USER" ] \
  || [ "$community_role" = "$POSTGRES_USER" ]; then
  echo "service database roles must differ from POSTGRES_USER" >&2
  exit 1
fi

if [ "$identity_role" = "$battle_role" ] \
  || [ "$identity_role" = "$judge_role" ] \
  || [ "$identity_role" = "$community_role" ] \
  || [ "$battle_role" = "$judge_role" ] \
  || [ "$battle_role" = "$community_role" ] \
  || [ "$judge_role" = "$community_role" ]; then
  echo "service database roles must be distinct" >&2
  exit 1
fi

for service in identity battle judge community; do
  database="d3_${service}"
  case "$service" in
    identity) role="$identity_role" ;;
    battle) role="$battle_role" ;;
    judge) role="$judge_role" ;;
    community) role="$community_role" ;;
  esac

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

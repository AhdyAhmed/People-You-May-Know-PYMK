#!/bin/sh
set -eu
: "${AIRFLOW_DB_PASSWORD:?Set a non-empty Airflow database password}"
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=app_password="$AIRFLOW_DB_PASSWORD" --set=ON_ERROR_STOP=1 <<'SQL'
CREATE ROLE airflow LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
ALTER DATABASE airflow OWNER TO airflow;
REVOKE ALL ON DATABASE airflow FROM PUBLIC;
ALTER SCHEMA public OWNER TO airflow;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL

#!/bin/sh
set -eu
# Entrypoint runs once on an empty, dedicated metadata volume. Never app Flyway.
: "${MLFLOW_DB_PASSWORD:?Set a non-empty MLflow database password}"
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=mlflow_password="$MLFLOW_DB_PASSWORD" --set=ON_ERROR_STOP=1 <<'SQL'
CREATE ROLE mlflow LOGIN PASSWORD :'mlflow_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
ALTER DATABASE mlflow OWNER TO mlflow;
REVOKE ALL ON DATABASE mlflow FROM PUBLIC;
ALTER SCHEMA public OWNER TO mlflow;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL

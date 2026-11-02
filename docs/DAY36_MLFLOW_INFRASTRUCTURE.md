# Day 36 — MLflow infrastructure and storage ownership

## Scope and boundaries

The optional `mlflow` Compose profile adds a working tracking UI/API, PostgreSQL
metadata and proxied persistent artifact storage. It does **not** automatically
track existing training commands, register serving models, promote releases or
change recommendation behavior. Opt-in tracking is now implemented in
[Day 37](DAY37_EXPERIMENT_TRACKING.md); registry/promotion remain Days 38–40. Java serving
still uses validated ONNX artifacts and the PostgreSQL release pointer; it never
calls MLflow. The base Compose profile remains PostgreSQL + Redis only.

Tested compatibility set: MLflow server/client **3.17.1**, Python image
`3.12.12-slim-bookworm`, PostgreSQL `16.15-bookworm`, psycopg2-binary `2.9.13` and
boto3 `1.42.49`. Direct dependencies/images are version-pinned; transitive Python
dependencies are resolved at image build time. Preserve the built image digest
and dependency inventory with backups/releases; this is not a fully locked
transitive environment. Training dependencies are unchanged and isolated.

## Start locally (PowerShell)

Docker Desktop must use Linux containers. Run from the repository root:

```powershell
# Generate independent development credentials. Save them securely outside Git
# if you want to reuse these volumes from another shell/session.
$env:PYMK_MLFLOW_DB_PASSWORD = [guid]::NewGuid().ToString('N')
$env:PYMK_MLFLOW_ADMIN_PASSWORD = [guid]::NewGuid().ToString('N')
$env:PYMK_MLFLOW_PORT = '5000'
docker compose -f infra/docker-compose.yml --profile mlflow up -d --build --wait mlflow
Invoke-WebRequest http://127.0.0.1:5000/health
```

Open [the local MLflow UI](http://127.0.0.1:5000). Both passwords are required for
first initialization; the application-role password must be at least 16 characters.
Do not regenerate passwords when reusing existing volumes: PostgreSQL init scripts
run only on first creation. Rotate an existing role explicitly and coordinate the
server configuration; changing an environment variable is not a DB password reset.
Never commit credentials, `.env` files, dumps or artifacts. Environment-based local
credentials are visible to Docker administrators; production requires managed
secrets, authenticated ingress and TLS. `docker compose config` without `--quiet`
can expose interpolated secrets.

The image runs as UID/GID 10001 without Linux capabilities. Only MLflow's HTTP
port is published, on `127.0.0.1`; its DB has no host port. The two services use a
separate Compose network, not the application's network. Allowed Host headers
are restricted to loopback and the internal `mlflow:5000` service; CORS allows
the configured loopback UI origin. Host validation is **not authentication**.
This is a single-user laptop profile: trusted local processes/network peers can
use it. Do not publish this profile to a LAN or the Internet unchanged. See
[MLflow network protections](https://mlflow.org/docs/latest/self-hosting/security/network/).

The server is capped at 2 GiB and metadata DB at 512 MiB, with one server worker.
These are configuration budgets, not measured production capacity or HA guarantees.
The health check covers HTTP and access to migrated metadata; the smoke test also
checks actual artifact write/read. Artifact storage exhaustion can therefore fail
uploads even when the basic health check is green.

Optional host client, in a separate environment:

```powershell
py -3.12 -m venv .venv-mlflow
./.venv-mlflow/Scripts/python.exe -m pip install -r infra/mlflow/client-requirements.txt
$env:MLFLOW_TRACKING_URI = 'http://127.0.0.1:5000'
```

## Storage and migrations

| State | Owner | Durable location |
|---|---|---|
| Runs, parameters, metrics, artifact references, future registry metadata | MLflow/Alembic | `pymk_mlflow_metadata` volume, dedicated `mlflow` database |
| Artifact bytes | MLflow HTTP artifact proxy | `pymk_mlflow_artifacts`, mounted at `/mlartifacts` |
| Application tables and release pointer | PYMK Flyway | Existing application database, unchanged through V11 |

The `mlflow_admin` bootstrap account creates a separate `mlflow` login with
`NOSUPERUSER NOCREATEDB NOCREATEROLE`. The runtime role owns only its database and
schema. `mlflow-migrate` runs **MLflow's** `db upgrade` before the server starts;
PYMK migrations must never be pointed at this database. No new Flyway migration
is part of Day 36. Missing/invalid credentials or migration failure prevents startup.

New experiments use `mlflow-artifacts:/` URIs. Clients upload/download over HTTP;
they do not need `/mlartifacts` mounted locally. Metadata and artifact bytes are
separate and must be backed up together. A run's artifact URI is recorded when
created; changing the destination does not migrate old artifacts automatically.
See [artifact storage](https://mlflow.org/docs/latest/self-hosting/architecture/artifact-store/)
and [metadata upgrades](https://mlflow.org/docs/latest/self-hosting/migration/).

## Backup, restore and upgrade policy

1. Stop admission and all writers, including the MLflow server and any direct DB
   or artifact writers. Keep PostgreSQL running. A DB dump taken while artifact
   uploads continue is not a consistent backup.
2. Take `pg_dump -Fc` of the metadata database as the bootstrap/backup account,
   and copy the entire artifact volume. Retain the same backup identifier,
   checksums, MLflow/image versions and database version. Store credentials
   separately. The automated smoke uses `docker cp` for binary-safe copies on
   Windows instead of PowerShell binary redirection.
3. Restore into **new volumes**, not over the only working copy. Initialize the
   dedicated roles, restore metadata with `pg_restore --no-owner --role=mlflow`,
   and restore artifact bytes to `/mlartifacts` as UID 10001. Preserve paths and
   artifact URIs. Verify the original run IDs, metrics and artifact hashes through
   the client before switching traffic.
4. For an MLflow version upgrade, preserve the old image and coordinated backup;
   build a new pinned server/client set and restore a clone. Stop that clone's
   server, run `mlflow-migrate` on it, then run write/read/restart tests with the
   new image. Only after successful clone validation upgrade the real instance
   during a maintenance window.
5. Do not assume database downgrades are supported. Rollback restores the paired
   metadata/artifact snapshot with its original image. Reconcile any later writes
   before switching; otherwise they are lost. Retain active/rollback model lineage
   when Day 38–40 introduces it.

Day 36 tests fresh-schema migration, repeat upgrade at the **same pinned version**,
container recreation, and restore into fresh volumes. It does not claim an
untested cross-version upgrade is safe. Day 40 extends recovery to real registered
model lineage and serving rollback. For a future upgrade, follow the clone
procedure and MLflow's release-specific migration notes, not a blind image bump.

Stop without deleting data:

```powershell
docker compose -f infra/docker-compose.yml --profile mlflow stop mlflow mlflow-db
```

Avoid `down -v` on a real project: it deletes its named volumes, potentially
including application/Kafka data. Only the smoke test's uniquely named disposable
projects are automatically removed.

## Shared S3-compatible storage (documented, not yet certified)

The image includes boto3. A deployment-specific Compose override can set these
environment values **on the MLflow server**, retaining its existing DB password:

```yaml
services:
  mlflow:
    environment:
      PYMK_MLFLOW_ARTIFACT_DESTINATION: s3://pymk-mlflow/artifacts
      MLFLOW_S3_ENDPOINT_URL: https://your-object-store.example
      AWS_DEFAULT_REGION: us-east-1
      AWS_ACCESS_KEY_ID: ${PYMK_S3_ACCESS_KEY}
      AWS_SECRET_ACCESS_KEY: ${PYMK_S3_SECRET_KEY}
```

Provision the bucket first; use least-privilege bucket/prefix credentials or a
workload identity, trusted TLS and a deliberate retention/versioning policy.
Keep artifact proxying enabled so workers need only authenticated HTTP access,
not object-store keys. Do not use `--no-serve-artifacts` or pass an explicit local
artifact path when creating experiments. Copy existing bytes before changing the
destination and verify every retained reference; never silently strand older runs.

**No S3 deployment or remote-worker support is claimed at this checkpoint.** Before
enabling either, test uploads/downloads from separate workers, checksums after server
recreation, access denial, object-store outage/retry, coordinated restore and TLS/auth
against the actual selected storage. Loopback-only local smoke cannot prove those.

## Repeatable acceptance

```powershell
./scripts/day36-mlflow-smoke.ps1
mvn -B -ntp verify
```

The infrastructure smoke uses uniquely named projects and generated disposable
credentials. It verifies the unchanged base profile, host UI/health, non-root
runtime, restricted DB role, rejection of untrusted Host headers, pinned client
logging, artifact proxy checksums, container recreation persistence, coordinated
backup/restore into separate volumes and repeat metadata upgrade. Both isolated
projects are cleaned up; evidence and backups remain under ignored
`artifacts/day36-smoke/`. CI runs the same script in a separate job.

### Verification record (2026-10-13)

- Image build and in-container `pip check` passed. Tested image ID:
  `sha256:3677922c9548f84e0c9e7be37123fa2e26cb3b6a744bf6595e216e531cefaf2f`.
  The smoke log includes the installed dependency inventory.
- Infrastructure smoke passed all checks above. The original run ID and SHA-256
  artifact checksum matched after recreation and fresh-volume restore
  (`artifacts/day36-infrastructure-smoke.log`,
  `artifacts/day36-smoke/4e0a60223d/`). Both disposable projects were removed;
  backup files and evidence were retained.
- Full `mvn -B -ntp verify`: all 13 reactor projects passed; 263 Java tests,
  zero failures, errors or skips (`artifacts/day36-verify.log`).
- Python regression: 57 tests passed with warnings treated as errors;
  `pip check` reported no broken requirements (`artifacts/day36-python.log`).
- An isolated launcher check rejected empty/short credentials and verified that
  reserved URL characters in passwords round-trip without changing the secret.
- Packaged application regression with MLflow stopped passed: fresh training,
  release activation, HTTP probabilities, caching, two rollbacks, durable event
  acceptance during Kafka outage, Redis-independent reconciliation, Kafka recovery
  and API restart with zero lag/no duplicate live effects
  (`artifacts/day36-serving-smoke.log`, `artifacts/day23-smoke/0134ebd6f7/`).
  Disposable services and volumes were removed; logs were retained.

These checks cover the stated local scenarios; they are not a guarantee of
defect-free operation, production security or untested remote-storage behavior.

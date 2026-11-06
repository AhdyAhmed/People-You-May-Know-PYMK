# Day 41 — Airflow infrastructure and workflow contracts

This checkpoint adds a runnable **foundation**, not scheduled production model
training. `pymk_runtime_contract` is a manual, paused-by-default DAG that validates
explicit UTC work parameters and runs the packaged Java ONNX verifier using a
separate Python trainer environment. It cannot publish models, modify application
data or activate releases. The production feature/model workflows begin Day 42;
reconciliation/backfills and durable cross-system retry identities follow later.

## Runtime boundary

- Pinned `apache/airflow:3.2.2-python3.12`, standard provider `1.13.1`, with the
  release's official Python constraints. Airflow remains isolated from the host
  training virtualenv and from an in-image `/opt/pymk/trainer` virtualenv.
- Java 21 (`eclipse-temurin:21.0.10_7-jre-jammy`) and the built `pymk-batch.jar` ship
  inside the worker image. The trainer has CPU-only PyTorch and pinned direct
  dependencies. Builds run `pip check` in both environments; transitive trainer
  dependencies are resolved at build time, not claimed fully lockfile-reproducible.
- `LocalExecutor` launches subprocesses in the scheduler container: parallelism 2,
  one active run/task per DAG, and a one-slot `pymk_jobs` pool. No Docker socket,
  host executable or privileged container is required. DAG tasks use fixed argument
  arrays, never interpolate supplied parameters into shell commands.
- Dedicated `airflow-db` PostgreSQL cluster and non-superuser `airflow` role;
  no published DB port. `airflow db migrate` owns metadata, **not PYMK Flyway**.
  `airflow-init` creates the resource pool before services start.
- Separate API, scheduler and DAG processor health checks. Metadata, task logs and
  artifact references persist in named volumes. DAG code is baked into the image;
  changes require a rebuild, not a writeable code mount.

Airflow's [LocalExecutor documentation](https://airflow.apache.org/docs/apache-airflow/3.2.2/core-concepts/executor/local.html)
explains why worker parallelism must be bounded. This local stack uses the
[Simple Auth Manager](https://airflow.apache.org/docs/apache-airflow/3.2.2/core-concepts/auth-manager/simple/index.html)
with an injected password, authentication enabled and loopback-only UI/API.
It is a development control plane, **not a public production deployment**. Do not
publish it to the LAN/Internet; production needs reviewed authentication, TLS,
authorization and secret management. Operators who can edit DAGs can execute code.

## Windows / Linux setup

On Windows use Docker Desktop with **Linux containers / WSL2**, PowerShell 7 and
Java 21/Maven for building the JAR. Do not install Airflow natively into Windows
or the existing trainer virtualenv. Linux uses the same container images. Initial
builds download the Airflow image, JRE and CPU training libraries and need several
GB of free disk/cache space.

From the repository root, build and test the Java application, then set fresh
local development secrets. Preserve them securely for later restarts; changing
the database password environment does not rotate an existing PostgreSQL role:

```powershell
mvn -B -ntp verify
$env:PYMK_AIRFLOW_ADMIN_PASSWORD = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$env:PYMK_AIRFLOW_DB_PASSWORD = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$env:PYMK_AIRFLOW_UI_PASSWORD = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$env:PYMK_AIRFLOW_JWT_SECRET = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$env:PYMK_AIRFLOW_FERNET_KEY = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).Replace('+','-').Replace('/','_')
docker compose -f infra/docker-compose.yml --profile airflow build airflow-api
docker compose -f infra/docker-compose.yml --profile airflow up -d --wait airflow-api airflow-scheduler airflow-dag-processor
```

Open [the local Airflow UI](http://127.0.0.1:8085) and log in as `operator` with
`PYMK_AIRFLOW_UI_PASSWORD`. Set `PYMK_AIRFLOW_PORT` to another free loopback port
if needed. Passwords/signing keys are neither in DAG source nor image layers.
Container environment variables remain visible to Docker administrators; do not
share inspection output or store secrets in committed files. No default password
is supplied, and missing/weak runtime secrets fail startup.

The normal profile still starts only application PostgreSQL and Redis. Enabling
Airflow does not implicitly enable Kafka/MLflow or connect to the serving DB.
Starting only the three named Airflow services avoids unrelated host port conflicts.

## Execute the workflow contract

Unpause `pymk_runtime_contract` and manually trigger it with:

```json
{
  "interval_start": "2026-01-01T00:00:00Z",
  "interval_end": "2026-01-02T00:00:00Z",
  "source_sha256": "<SHA-256 of the shipped model.base64 fixture>",
  "output_version": "runtime-check-v1"
}
```

Obtain the fixture digest without exposing credentials:

```powershell
docker compose -f infra/docker-compose.yml --profile airflow exec airflow-scheduler sha256sum /opt/pymk/model.base64
```

The manual work interval must use full UTC timestamps (`YYYY-MM-DDTHH:MM:SSZ`,
optionally with fractional seconds), be positive, at most one day
and already ended. It is not silently inferred from Airflow's logical date or
trigger time. The source digest must match the shipped fixture. Output versions
must be safe identifiers. Invalid parameters fail before launching inference.
`catchup=False`, one retry after 30 seconds, a four-minute task timeout and a
ten-minute DAG-run timeout are explicit. No automatic schedule is enabled.

The probe loads a fixed L1 ONNX fixture in Python and performs batch/singleton
parity through the actual Java production loader. Heavy/tracking libraries are
imported in the isolated job, not during DAG parsing. Successful output is a small
immutable JSON report under `/opt/airflow/artifacts/runtime-contract/`; XCom carries
only its path and SHA-256. The report records the packaged JAR digest, runtime
versions and parity checks. Repeating identical work validates/reuses its report;
changed evidence for the same logical key is rejected. This narrow fixture retry
contract is **not** Day 44's durable Spring Batch/MLflow execution mapping.

## Automated acceptance and operation

```powershell
./scripts/day41-airflow-smoke.ps1
```

The script uses unique disposable Compose projects and generated secrets. It
checks that the base profile is unchanged, imports/tests DAG structure with no
network, checks both Python environments, starts real scheduler services, verifies
authentication, executes a successful DAG and an invalid-interval failure, and
recreates services while checking retained metadata/artifact/log evidence.
`-SkipBuild` reuses a previously built image. It removes only its disposable
project volumes; **do not copy `down -v` into normal operation**.

For a non-destructive stop of your own Airflow services:

```powershell
docker compose -f infra/docker-compose.yml --profile airflow stop airflow-scheduler airflow-dag-processor airflow-api airflow-db
```

Use the startup command with the same secrets/volumes to resume. Back up the
dedicated metadata DB, logs and artifact volume before changing pinned versions.
There is no public API dependency on Airflow: its downtime delays future scheduled
work, not existing recommendation serving. Full orchestrated training, backfills,
crash recovery and deployment remain explicitly scoped to Days 42–45.

## Acceptance measurements

Verified on **2026-10-15**, with final evidence reviewed on **2026-10-16**:

- Full Maven reactor: **275 tests, zero failures, errors or skips**, BUILD SUCCESS.
- Trainer suite: **106 tests passed**, with warnings treated as errors.
- Offline Airflow contract/startup suite: **7 tests passed** without network access.
- Live acceptance: authenticated API, actual LocalExecutor Java/Python inference,
  identical-input replay, expected invalid-interval rejection, and retained
  metadata/artifacts/logs after service recreation all passed. Both Python
  environments passed dependency checks; runtime isolation and restricted DB-role
  checks passed. The smoke process exited successfully and removed its disposable
  project containers and volumes.

Local evidence (ignored build artifacts): `artifacts/day41-verify.log`,
`artifacts/day41-python.log`, `artifacts/day41-airflow-final.log`, and
`artifacts/day41-smoke/e367ade316/`.

The final post-run resource snapshot on Docker Desktop (8 CPUs, approximately
23.4 GiB available memory) was:

| Service | Memory | CPU snapshot |
| --- | ---: | ---: |
| API | 252.6 MiB | 0.13% |
| Metadata PostgreSQL | 54.12 MiB | 1.41% |
| Scheduler / LocalExecutor | 309.3 MiB | 2.48% |
| DAG processor | 192.7 MiB | 0.95% |

This approximately 809 MiB total is a **post-run snapshot, not peak consumption**.
Configured ceilings are 3 GiB for scheduler/workers, 2 GiB each for API and DAG
processor, and 512 MiB for metadata PostgreSQL; init runs only during startup.
Allow at least an 8 GiB Docker memory budget for this isolated foundation profile
and additional headroom when running the application stack. Full Kafka/MLflow/
training-stack peak usage was not measured. The fixture is a runtime check, not a
production training benchmark, and these passing checks do not prove absence of
all possible defects.

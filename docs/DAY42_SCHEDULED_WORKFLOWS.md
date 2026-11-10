# Day 42 — scheduled managed features and model candidates

This is the Day 42 acceptance record. The [Day 44 recovery runbook](DAY44_WORKFLOW_RECOVERY.md)
supersedes its zero-retry and existing-destination rejection rules with bounded,
identity-checked recovery; the original acceptance evidence below remains historical.

Two real Airflow DAGs invoke the shipped Java 21 batch JAR and isolated Python
trainer environment. Both are paused at creation and use explicit UTC data
interval timetables, `catchup=False`, one active run/task and the shared single-slot
`pymk_jobs` pool. They do not activate, roll back, publish registry aliases or run
the legacy in-place feature writer.

| DAG | Cadence (UTC) | Tasks |
| --- | --- | --- |
| `pymk_feature_refresh` | Daily at 02:00 | Validate pinned sources → generate vectors with frozen transform → stage managed features |
| `pymk_model_candidate` | Sunday at 04:00 | Validate mature history → registered v1/v2 exports → tracked L1 → tracked L2 → frozen evaluation → validation-only blend tuning → Java-validated candidate preparation |

These clocks are deliberately independent: a successful fresh refresh is **not**
a training dependency. Candidate inputs must name at least three existing managed
historical generations with nonoverlapping label horizons. Their labels must be
mature by the declared interval end, not merely by the worker's current time.
The existing trainer additionally enforces chronological split and provenance
contracts. Missing history cannot be replaced with today's profiles.

## Runtime and opt-in connections

Follow [Day 41 setup](DAY41_AIRFLOW_FOUNDATION.md) to generate Airflow secrets.
Start the normal application PostgreSQL separately. Supply the database password
through `PYMK_WORKFLOW_DB_PASSWORD`; optional `PYMK_WORKFLOW_DB_USER` and
`PYMK_WORKFLOW_JDBC_URL` default to `pymk` and
`jdbc:postgresql://postgres:5432/pymk`. Use the existing MLflow setup to generate
`PYMK_MLFLOW_DB_PASSWORD` and `PYMK_MLFLOW_ADMIN_PASSWORD`.

```powershell
mvn -B -ntp -pl pymk-api,pymk-batch -am -DskipTests package
docker compose -f infra/docker-compose.yml -f infra/airflow-workflows.yml --profile airflow --profile mlflow build airflow-api mlflow
docker compose -f infra/docker-compose.yml -f infra/airflow-workflows.yml --profile airflow --profile mlflow up -d --wait mlflow airflow-api airflow-scheduler airflow-dag-processor
```

The overlay explicitly connects workers to the application and MLflow networks;
the foundation profile alone remains isolated. MLflow publication is mandatory
for training/evaluation/tuning success; an unavailable server fails the task and
retains local evidence. Credentials come from environment, never DAG source,
workflow manifests or command arguments. Local development credentials and the
SimpleAuthManager are not a public-production security configuration.

## Immutable workflow manifests

All inputs and outputs live under the shared `/opt/airflow/artifacts` volume.
An artifact reference is exactly `{"path":"/opt/airflow/artifacts/...","sha256":"<64 lowercase hex>"}`.
Only references cross XCom. Operators must provision immutable inputs and a
reviewed JSON manifest before enabling a schedule; this is not an autonomous
snapshot collector or historical reconstruction service.

To provision a reviewed local input bundle, create a **new** volume directory
(omit `-p` so an existing identity is not silently reused), then copy its files:

```powershell
docker compose -f infra/docker-compose.yml exec -T airflow-api mkdir /opt/airflow/artifacts/reviewed-v1
docker compose -f infra/docker-compose.yml cp artifacts/reviewed-v1/. airflow-api:/opt/airflow/artifacts/reviewed-v1/
```

Manifest references must use the container paths, not Windows paths. Compute
SHA-256 after copying, and publish the envelope only after all referenced files
are present. Do not modify that bundle after publication. The local folder name
above is an example, not a bundled production dataset.

Feature manifest shape (replace references and revision with verified values):

```json
{
  "schemaVersion": 1,
  "workflow": "feature",
  "intervalStart": "2026-10-14T02:00:00Z",
  "intervalEnd": "2026-10-15T02:00:00Z",
  "outputVersion": "features-20261015-v1",
  "sourceRevision": "123",
  "transform": {"path": "/opt/airflow/artifacts/inputs/transform.json", "sha256": "<sha256>"},
  "profiles": {"path": "/opt/airflow/artifacts/inputs/profiles.csv", "sha256": "<sha256>"}
}
```

The transform must already be frozen (Day 22); scheduled refresh never refits it.
The feature snapshot is the interval end. The pinned profile export must be
appropriate to that cutoff. `sourceRevision` is the explicit value of
`SELECT revision FROM pymk_source_revision`; the Java adapter checks it before
and after the job, and verifies that the staged generation binds that revision.
Changing sources fail closed and require a newly reviewed manifest/version.
Existing Day 23 source locks, exact member coverage and immutable generations
remain authoritative; previously staged/active generations are retained.

A candidate manifest uses the same common fields, `workflow: "candidate"`, and
replaces `profiles` with:

- `history`: at least three objects containing `releaseId`, `featureAsOf` and
  `labelWindowEnd`, strictly ordered with nonoverlapping label horizons.
- `evaluationPolicy`, `blendPolicy`, `context`: checksum-bound references to
  the existing evaluation/tuning policies and frozen cohort/employer context.

Blend context must bind the exact expected v2 CSV hashes, following the existing Day 30
contract. Prepare it from verified mature exports; the scheduled re-export must
reproduce those bytes or tuning rejects it. Evaluation uses the frozen exported
cohort; it does not claim full-funnel L0 replay or treat blend context as that
different optional evaluator format. Production workflows do
not call the synthetic context generator. Only the disposable smoke does, with
explicit constant-profile assumptions. The source revision also pins the label
source during export. The worker checks each exported snapshot against the plan;
the trainers check registered provenance and frozen-transform compatibility.

For a scheduled run, place a small reference envelope at:

```text
/opt/airflow/artifacts/inbox/feature/YYYYMMDDTHHMMSSZ.json
/opt/airflow/artifacts/inbox/candidate/YYYYMMDDTHHMMSSZ.json
```

The filename is the **UTC data interval end**; the referenced manifest's start
and end must exactly match the scheduled interval. Missing envelopes fail visibly
without substituting data. For manual runs, supply `{"manifest": <reference>}`
in DAG configuration; the manifest's explicit interval is used, not the manual
trigger date. [Airflow's interval semantics](https://airflow.apache.org/docs/apache-airflow/3.2.2/core-concepts/dag-run.html)
explain why logical date, interval end and wall-clock execution are distinct.

## Completion, failures and release boundary

The Java adapter allowlists only `embeddingRefreshJob` and the `export`/`export-v2`
actions of `servingReleaseJob`, waits for terminal `COMPLETED` status, and writes
an execution-ID receipt. Subprocess failures and timeouts fail their Airflow task.
Task outputs live under `workflows/<outputVersion>/<step>/`; result manifests bind
the input plan and all preceding artifacts. Altered predecessors are rejected.

Training, evaluation and blend computations must have acknowledged MLflow
tracking. Registry preparation then reuses the Day 38 production Java verifier
for both L1/L2 and includes exact evaluation/blend lineage. A failed quality gate
still produces an auditable **ineligible** candidate, never permission to deploy.
The final receipt explicitly records `activationEligible: false` and every
result records `automaticPromotion: false`. Approval, registry publication/sync
and expected-epoch activation are outside these DAGs (Day 44).

Automatic retries are **zero** until Day 44 implements durable execution mapping
and crash recovery. A failed or repeated output directory is not overwritten.
Pause the DAG, inspect the retained logs/receipts and Spring Batch execution,
resolve the cause, then submit a newly reviewed output version. Do not clear tasks
to force reuse, delete prior generations, or bypass a quality gate. Each worker
command has a 20-minute timeout, each task a 56-minute limit, and each DAG a
five-hour limit. Production sizing still requires a representative benchmark.

## Acceptance

```powershell
./scripts/day42-airflow-workflows-smoke.ps1
```

The smoke creates a unique disposable application DB, Airflow and MLflow stack.
It prepares synthetic historical generations, then uses the authenticated real
scheduler for a fresh feature DAG and the complete mature candidate DAG. It also
injects immature labels and an incomplete-profile refresh, verifies no active
pointer change and retention of prior generations, and runs the Day 41 runtime
regression. Containers and volumes from only that project are removed; worker
artifacts and task/service logs are copied to ignored `artifacts/day42-smoke/`.

Verified **2026-10-16**:

- Full `mvn -B -ntp verify`: **278 tests, zero failures/errors/skips**, BUILD SUCCESS.
- Trainer suite with `-W error`: **106 tests passed**.
- Image-contained offline Airflow suite: **17 tests passed** without network access.
- Authenticated real-scheduler feature and complete model-candidate runs passed:
  registered mature exports, acknowledged tracked L1/L2 training, frozen
  exported-cohort evaluation, validation-only blend tuning and Java-validated
  candidates. The tiny fixture correctly produced a `not_met` quality gate and
  remained ineligible for activation.
- Injected immature-label and incomplete-profile cases failed as expected.
  Previous generations and the active pointer were unchanged. The Day 41
  authenticated runtime/parity/replay/invalid-interval regression also passed.
- PowerShell syntax, local Markdown file links and unchanged base Compose
  services (`postgres`, `redis`) checked successfully.

The live DAGs are manually triggered with deterministic reviewed manifests through
the real scheduler, not task-test shortcuts. Cron/UTC interval selection is covered
by offline tests. Unprovisioned current scheduled intervals fail closed on their
missing inbox envelope; they do not substitute the fixture for current data.

Final local evidence (ignored artifacts): `artifacts/day42-verify-final.log`,
`artifacts/day42-python-final.log`, `artifacts/day42-airflow-acceptance.log`, and
`artifacts/day42-smoke/6bab016c16/` (worker artifacts, service log and portable
`task-logs.tar.gz`). The disposable project is cleaned up after evidence capture.

Earlier runs are retained, **not counted as passing acceptance**. Live testing
found and fixed missing interval arguments for manual runs and accidental use of
blend context as full-funnel evaluation context; regression tests cover both.
Scheduled log names contain colons, so Windows evidence capture now archives
them instead of attempting invalid Windows paths. One intermediate run was
interrupted by service shutdown signals: its candidate export stopped and the
concurrent Maven tests lost PostgreSQL. Both were rerun from fresh isolated
services; only the successful final logs above are the acceptance record.

This fixture checks implementation correctness, not real-world model quality or
production capacity, and passing tests do not establish absence of every possible
defect. Day 43 reconciliation/backfills and Days 44–45 durable recovery/release
tests remain separate roadmap work.

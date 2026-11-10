# Day 44 — durable workflow recovery and controlled releases

This is the Day 44 acceptance record. Day 45 adds a reviewed daily synchronization
schedule and offline reporting; activation remains manual. See the
[Day 45 runbook](DAY45_ORCHESTRATION_RESILIENCE.md) for current scheduling/operations.

## Ownership and identities

Flyway V13 adds `pymk_workflow_work` to the **PYMK** database, not Airflow's
metadata database. A destination version/step is permanently bound to a canonical
work key covering the manifest contents (including intervals, source/configuration
hashes and output version), predecessor receipt, worker/recovery code, packaged
Java verifier, trainer requirements and tracking destination. A new trigger filename
containing identical manifest contents does not create new work. Changed inputs,
runtime or destination require a reviewed new output version.

The ledger records `RUNNING`, `COMPLETED` or `BLOCKED`, attempt count, output
receipt/hash, Batch instance/execution receipts and MLflow computation/publication
state references. It is diagnostic control-plane evidence, not serving data.
Only verified receipts may be marked completed. An Airflow success state or an
existing directory alone is insufficient.

All supported worker entry points acquire a PostgreSQL session advisory lock on
destination/step. A Linux `flock` descriptor is inherited by the Java/Python child;
if the supervisor is killed, another worker cannot race the still-running child.
The configured artifact root is also bound in the ledger. Manual workers must use
the same shared artifact volume and database as scheduled workers. Multi-host
filesystems without reliable inherited POSIX locking are not supported by this
local deployment contract.

`WorkflowBatchRunner` independently fences its job/parameter identity, includes
the source revision in identifying parameters, and consults Spring Batch before
launching. A completed execution reconstructs the **same** receipt, instance and
execution ID. A running/unknown execution is not forcibly marked failed or blindly
relaunched. Existing database source/activation locks still protect direct Batch
and release-service invocations outside this adapter.

## Recovery contract

| Observed condition | Behavior |
|---|---|
| Phase receipt exists | Revalidate plan, predecessor and every immutable output hash; return the original reference. |
| Command completion marker exists | Verify invocation identity and retained outputs; do not launch another computation. |
| Batch committed but receipt/acknowledgement was lost | Query the same identifying Batch parameters and recreate its receipt without running the completed job. |
| Training completed; publication/phase acknowledgement missing | Reuse its retained tracking bundle, verify the completed computation and resume M7 publication; never retrain into its existing model version. |
| Candidate/synchronization completion proof exists | Revalidate pinned source/verifier and full output inventory rather than repeat preparation/download. |
| A child still owns the inherited lock | Reject competing work, even if the supervisor's database session has gone away. |
| Timeout, incomplete computation or unknown Batch execution | Retain evidence and block uncertain work. Inspect the owner and durable effects; do not delete directories, invent a new timestamp identity or edit Batch state to force success. |

Normal DAGs now have one bounded retry with a 30-second delay. Approval activation
has **zero automatic retries**. Retries are useful for lost acknowledgements and
recoverable publication failures, not permission to overwrite uncertain output.
An incomplete non-idempotent command stays blocked for operator review. Remote
publication retains M7's documented uncertain-acknowledgement limits: one logical
computation may have multiple recorded publication attempts; exactly-once remote
MLflow registration is not claimed.

`registry-state.json` is mutable publication bookkeeping, not an immutable model
artifact. It is excluded from artifact inventories; candidate manifests and model
bytes remain checksum-bound. Keep the state alongside its stage for M7 recovery.

## Startup and operator recovery

Use the [Day 42 overlay/setup](DAY42_SCHEDULED_WORKFLOWS.md) and migrate the PYMK
database with the current application before triggering DAGs. Workers run in the
packaged Linux Airflow Python environment for PostgreSQL locking and launch ML
commands in the separate pinned trainer environment. Credentials remain injected
through `SPRING_DATASOURCE_*`; do not put them in manifests or command arguments.
The JDBC URL must use PostgreSQL and separate credentials, without query options.

To inspect a stalled work item, correlate:

```sql
SELECT output_version, step, logical_key, state, attempts, evidence, updated_at
FROM pymk_workflow_work
ORDER BY updated_at DESC;
```

1. Pause the affected DAG and establish whether its worker/child still exists.
   Do not force-release a lock while a child might still produce effects.
2. Inspect the retained task/worker log, command marker, Batch execution status
   and tracking bundle. A supervisor crash may leave `RUNNING` after its lock is
   gone; a retry reconciles durable completion under fresh ownership.
3. Retry the **same** manifest/destination after restoring a missing service when
   completion evidence is intact. Clearing an Airflow task or submitting an
   equivalent manual trigger must reuse the original receipts.
4. For a `STARTED`/`UNKNOWN` Batch execution without verified completion, investigate
   database effects and the original process. This implementation deliberately
   does not rewrite Spring Batch metadata automatically. An uncertain partial
   computation may require a reviewed new version after its owner is stopped;
   preserve the old evidence and account for any committed effects first.

Back up the ledger with PYMK data and preserve artifacts with their exact paths.
Restoring Airflow metadata alone cannot recreate model/Batch completion evidence.
Runtime upgrades intentionally invalidate reuse of an existing destination; retain
the old image for recovery or submit a new reviewed version.
Use new destination versions when upgrading pre-ledger (Day 43) work: the new
ledger cannot retroactively prove which runtime produced older artifacts.

## Controlled release workflows

Both workflows below are manual-only, start paused, and share the bounded
`pymk_jobs` pool. Neither generates an approval or infers approval from a quality
gate, registered model, successful DAG or mutable MLflow alias.

### Synchronize and stage

Register exact candidates and extend a schema-2 release through the existing
[M7 workflow](DAY38_MODEL_REGISTRY.md). Trigger `pymk_release_sync` with a referenced
manifest containing exactly:

```json
{
  "schemaVersion": 1,
  "workflow": "release_sync",
  "intervalStart": "2026-10-15T00:00:00Z",
  "intervalEnd": "2026-10-16T00:00:00Z",
  "outputVersion": "reviewed-sync-v1",
  "sourceRevision": "123",
  "release": {"path": "/opt/airflow/artifacts/reviewed-release.json", "sha256": "<exact SHA-256>"}
}
```

It runs M7 `model_sync`, verifies numeric registry pins and production Java parity,
then stages the synchronized manifest through managed Batch generation staging.
It does not change the active release. Its final bundle lives at
`workflows/<outputVersion>/sync/bundle`; do not relocate it after approval.

### Explicit approved activation

Use the [Day 39 rehearsal/approval procedure](DAY39_GUARDED_PROMOTION.md) to prepare
real evidence, exact deployment/rollback properties and `approval.json` in the
synchronized stage. Keep the independent trusted-operator review; synthetic smoke
measurements are never deployment evidence.

Only after draining the application as required, enable
`PYMK_WORKFLOW_ALLOW_ACTIVATION=true` explicitly in the operator deployment and
recreate the affected Airflow worker services with that setting. Trigger the
separate `pymk_approved_activation` DAG with a referenced manifest containing:

- The same common `schemaVersion`, interval bounds, new `outputVersion` and
  explicit `sourceRevision` fields.
- `workflow: "approved_activation"`.
- `release`: reference to the exact synchronized `release.json`.
- `approval`: reference to that stage's `approval.json`.
- `expectedEpoch`: the reviewed nonnegative integer epoch.
- `legacyModel` and `legacyMetadata`: checksum references to retained baseline
  rollback artifacts inside the configured artifact root.
- `operatorDrainConfirmed: true`: explicit trusted-operator confirmation, **not**
  an automated check that external traffic is drained.

The adapter accepts only `activate-approved`; legacy `activate` and automatic
rollback remain unavailable. The existing M7 implementation rechecks complete
sync inventory, approval lifetime, exact manifest/epoch, temporal provenance,
quality, retained rollback data and process settings under database fencing.
Stale/incomplete approval fails before changing the serving pointer. Disable the
activation flag again after the controlled operation.

L2/blend settings still require the operator-controlled drained process restart.
An epoch switch does not hot-swap a running JVM's heavy model. Rollback remains the
explicit M7 operator procedure; no scheduler autonomously rolls serving back.

## Acceptance

```powershell
mvn -B verify
./pymk-ml-training/.venv/Scripts/python.exe -W error -m unittest discover -s pymk-ml-training/tests -v
./scripts/day42-airflow-workflows-smoke.ps1 -IncludeDay44
```

The disposable smoke includes Days 41–43 regressions, lost Batch acknowledgement,
supervisor death with a surviving child, replay with unchanged Batch/MLflow
identities before and after scheduler restart, conflicting-manifest rejection,
numeric-pinned synchronization/staging and disabled activation rejection. Unit
tests cover actual subprocess timeout, corrupted outputs and stale approval
bindings; existing M7 Java tests retain positive approved activation, stale epoch,
concurrent approval and rollback coverage. Full packaged HTTP activation/rollback
and expanded outage/restore scenarios remain Day 45.

Verification on 2026-10-17:

- Full `mvn -B verify`: **280 tests**, zero failures/errors/skips; all 13 reactor
  projects succeeded. Evidence: `artifacts/day44-verify-continuation.log`.
- Python suite with warnings treated as errors: **106 tests passed**.
  Evidence: `artifacts/day44-python-continuation.log`.
- Packaged Airflow offline suite: **24 tests passed**.
  Evidence: `artifacts/day42-smoke/ba97db3eaa/contracts.log`.
- PowerShell smoke-script syntax validation passed.

- Complete live Days 41–44 acceptance passed, including actual Java/Python
  inference, feature/model workflows, retained-export backfill, reconciliation,
  lost acknowledgement, inherited-child fencing, numeric-pinned synchronization,
  disabled activation, conflicting inputs and replay before/after scheduler restart.
  Evidence: `artifacts/day44-acceptance-continuation.log` and
  `artifacts/day42-smoke/ba97db3eaa/` (worker artifacts, service logs and task-log archive).
  The smoke exited zero and removed its disposable project containers/volumes.

**Day 44 complete.** Earlier interrupted runs are not counted as passes. The earlier Java migration
assertion failure was fixed to verify V12 and V13 separately; the full fresh
verification above includes that regression.

# Day 45 — orchestration resilience and operator runbook

Implemented and verified on 2026-10-18. Day 45 completes M8; unified
observability remains M9. Acceptance scope and fixture limits are recorded below.

## Scheduled work and release boundaries

All DAGs start paused, use UTC, `catchup=false`, one active run/task per DAG,
and the shared single-slot `pymk_jobs` pool. Normal tasks have one retry after
30 seconds; approved activation has none. Database and inherited child locks
also fence direct workers, independently of Airflow's pool.

| DAG | Schedule | Required reviewed inputs / result |
|---|---|---|
| `pymk_feature_refresh` | Daily 02:00 UTC | Frozen profiles/transform; stage, never activate |
| `pymk_live_reconciliation` | Daily 03:00 UTC | Explicit event/ingestion cutoffs; finite repair |
| `pymk_model_candidate` | Sunday 04:00 UTC | Mature generations; tracked training/evaluation/candidates |
| `pymk_release_sync` | Daily 05:00 UTC | Exact schema-2 registry release; download/verify/stage only |
| `pymk_offline_report` | Monday 06:00 UTC | Retained exports and pinned models; tracked evaluation only |
| `pymk_historical_backfill` | Manual | Retained historical revisions/exports; new candidates |
| `pymk_approved_activation` | Manual | Independent M7 approval, epoch and drain confirmation |

Scheduled runs read `inbox/<workflow>/<interval-end-YYYYMMDDTHHMMSSZ>.json` under
the configured artifact root. Each envelope contains a `path` and `sha256` for
the manifest. Its interval must exactly match Airflow's data interval. No inbox
entry means failure, not an implicit latest-model lookup or skipped success.
Keep DAGs paused until a reviewed input producer supplies those envelopes.
Day 44's manual sync manifest remains supported as an explicit trigger.

`offline_report` uses the same common fields and retained `history` entries as
`backfill`, but replaces `blendPolicy` and `context` with four checksum references:
`lightModel`, `lightMetadata`, `heavyModel`, `heavyMetadata`. Keep
`evaluationPolicy` and `transform`. It restores exact CSV/provenance bytes and
invokes the existing tracked heavy evaluator. It does not train, register,
approve or activate. A failed quality gate is a recorded evaluation outcome,
not permission to promote. Runtime changes require new output versions.

## Pause, resume and recovery

Use the authenticated Airflow UI to pause the affected DAG. Pausing prevents
new scheduled work; it does **not** stop an already running worker or child.
Inspect the DAG/task logs and PYMK work ledger before stopping workers.
For planned maintenance, drain tasks, stop scheduler/worker processes and verify
that no child remains. A stale `RUNNING` row requires investigation, not a forced
state edit. Use the [Day 44 recovery matrix](DAY44_WORKFLOW_RECOVERY.md) to retry
the same manifest when durable completion is verified. Preserve incomplete work.

Manual recovery uses the same packaged entry point as scheduled work:

```sh
python /opt/pymk/workflow.py --request /opt/airflow/artifacts/reviewed-request.json
```

The request contains `plan` (manifest reference), `step`, and `previous` (prior
receipt reference or null for validation). It is not an arbitrary command API.
Use the same image, credentials, artifact root and database as the original run.
After the cause is resolved, clear the affected Airflow task or explicitly trigger
the same work; completed effects are revalidated and reused. Do not change
versions merely to bypass unknown partial effects.

Approved activation remains a trusted local-operator boundary, not an external
authorization service. Drain serving traffic, obtain real independent M7 evidence,
enable the activation flag only for the controlled operation, and restart serving
with the approved process settings. Disable the flag afterward. Rollback is the
explicit expected-epoch M7 procedure; Airflow never autonomously rolls serving back.

## Telemetry and alert ownership

Flyway V14 extends the PYMK ledger with workflow/interval, attempt start,
completion time, last attempt duration and cumulative failure count. Old V13 rows
have unknown telemetry until revisited; unknown freshness is never reported as zero.
Failed attempts remain counted even after successful recovery. Duration measures
the worker attempt, not queue time or end-to-end DAG duration.

```sh
python /opt/pymk/operations.py status
python /opt/pymk/operations.py metrics
```

The first command emits a JSON snapshot; the second emits Prometheus text for a
collector to publish atomically to its textfile directory. Collection failure exits
nonzero; do not retain stale output as if freshly sampled. Labels are bounded
workflow, step and state, never member/run/release IDs. Metrics expose item counts,
failed attempts, maximum last-attempt duration and completed data-interval age.
These are operational CLI exports, not an installed scraper/dashboard/notification
service. M9 owns that integration. Airflow remains the source of DAG queue/run timing.

The local operator owns the Airflow failed-task queue and `BLOCKED` ledger rows.
Investigate any newly blocked work or failure-count increase. For freshness, compare
the final phase's completed interval against its expected schedule plus an explicit
maintenance allowance (daily: 26 hours; weekly: 8 days as initial local thresholds).
Manual workflows have no automatic freshness SLA. No completed interval is unknown,
not healthy. Check scheduler heartbeat and API health separately: no failed rows
cannot prove that a stopped scheduler is making progress. Never alert solely on
the latest validation phase when the final stage/evaluation is stale.

## Backup and retention

Pause/drain writers before coordinated backups. Preserve PYMK data (including
Batch metadata, V13/V14 ledger and approval audit), Airflow metadata, MLflow metadata
and artifact bytes, and the shared workflow volume. Preserve image digests, paths,
roles and injected secrets separately. Restore all planes to a consistent point;
Airflow metadata alone cannot reconstruct committed jobs or artifacts. Test restore
in an isolated project before touching a live deployment. The acceptance smoke
restores stopped Airflow metadata while retaining its matching PYMK/artifact planes;
the M7 lifecycle smoke exercises coordinated MLflow metadata/artifact restoration.

Retention defaults to a dry run:

```sh
python /opt/pymk/operations.py retention --days 30
# Only after pausing DAGs, stopping workers and reconciling RUNNING entries:
python /opt/pymk/operations.py retention --days 30 --apply --quiesced
```

This conservatively archives old UUID request envelopes to `request-archive/`
without overwriting existing files. It refuses retention under seven days and
refuses apply while ledger work is `RUNNING`. Quiescence is an explicit trusted
operator assertion, not an automatic Airflow stop. Files are hard-linked before
removing the original directory entry, so interrupted cleanup retains a copy.
An interrupted operation with both names present requires operator reconciliation.
Model/data outputs, active releases, rollback artifacts, approvals, lineage, logs
and ledger history are never deleted. Broad artifact garbage collection is not
implemented: operators must not infer deletion permission from an old timestamp.

## Acceptance

```powershell
mvn -B verify
./pymk-ml-training/.venv/Scripts/python.exe -W error -m unittest discover -s pymk-ml-training/tests -v
./scripts/day42-airflow-workflows-smoke.ps1 -IncludeDay45
./scripts/day40-lifecycle-smoke.ps1
./scripts/day23-release-smoke.ps1 -IncludeBlendTuning -IncludeObservability
```

The Day 45 orchestration smoke includes Days 41–44, frozen offline-report replay,
L1-only pinned synchronization and actual approved-activation DAG wiring,
packaged HTTP recommendations, stopped Airflow/MLflow serving isolation,
Airflow metadata restore/replay, missing-artifact rejection/restoration, failure
telemetry, conservative retention and drained operator rollback with HTTP checks.
Its positive approval uses explicitly synthetic contract evidence (like the Java
approval integration tests), not measured production load/quality evidence. It
does not approve the deliberately failing heavy-quality fixture. No such fixture
approval is suitable for a real deployment.

The lifecycle smoke separately checks MLflow artifact-store outages and coordinated
restoration. The serving/Kafka smoke checks actual Kafka stop/restart, retained
events, HTTP serving and process-level L2/blend rollback. Neither a passing smoke
nor these small synthetic cohorts constitutes production readiness or a universal
absence-of-defects guarantee.

Verification completed on 2026-10-18:

- Python: 106 tests passed with warnings treated as errors (`artifacts/day45-python.log`).
- Packaged Airflow: 28 offline tests passed (`artifacts/day42-smoke/825816b7f9/contracts.log`).
- MLflow lifecycle: all seed/outage/backup/restore phases passed; process exited zero,
  and both disposable projects were removed. Evidence: `artifacts/day45-lifecycle.log`
  and `artifacts/day40-smoke/e5115853de/`.
- Full `mvn -B verify`: 280 tests, zero failures/errors/skips; all 13 reactor
  projects succeeded (`artifacts/day45-verify-final.log`, 13:19).
- Live Days 41–45 orchestration resilience passed: approved activation, real HTTP
  serving with Airflow/MLflow stopped, metadata restore and effect reuse, registry
  outage/recovery, artifact-loss rejection, telemetry and retention. The rollback
  Batch job completed successfully and the restarted API passed its HTTP check.
  Evidence: `artifacts/day45-orchestration-final.log` and
  `artifacts/day42-smoke/825816b7f9/`.
- Packaged serving/Kafka smoke passed: actual training, L2/blend serving and
  rollback, broker outage/recovery, independent consumers, reconciliation,
  monitoring and restart durability. Evidence: `artifacts/day45-serving-kafka-final.log`
  and `artifacts/day23-smoke/c41a11abc5/`.
- Disposable smoke containers and volumes were removed after evidence capture;
  these acceptance services are not a persistent running deployment.

The initial orchestration image build exposed a Docker-context exclusion for the
new API JAR. The allowlist was corrected and the rebuilt image passed its offline
suite. The failed initial build is not counted as passing acceptance.
Earlier Java/orchestration/serving attempts then lost access to their Docker-backed
services around 00:27 local time and failed; those logs are retained and are not
counted as passes. Fresh reduced-concurrency reruns use the `day45-verify-final.log`
and `day45-orchestration-final.log` evidence files.
The successful reruns, not the interrupted attempts, establish this checkpoint.

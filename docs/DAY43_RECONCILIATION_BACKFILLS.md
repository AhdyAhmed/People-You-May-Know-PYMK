# Day 43 — reconciliation and reproducible backfills

## Runtime boundaries

`pymk_live_reconciliation` runs daily at 03:00 UTC (or manually). It invokes the
finite `liveFeatureReconciliationJob`, waits for completion and records the Batch
execution ID, repaired generation and declared watermarks. Continuous Kafka
consumers remain in `pymk-event-stream`, not Airflow tasks.

`pymk_historical_backfill` is manual-only. It restores retained v1/v2 training
exports into a new output directory, then runs the real tracked L1, L2,
evaluation, blend and Java-validated candidate preparation steps. It does not
create a new database dataset registration, fabricate historical profiles,
re-export present-day labels, sync a model or activate a serving release.

Both DAGs have one active run and one active task independently of normal
feature/candidate DAGs. All share the one-slot `pymk_jobs` resource pool. Direct
manual reconciliation still takes the same PostgreSQL admission advisory lock
and active-generation row lock as scheduled reconciliation. Journal writes wait
for generation replacement and then target the new generation. Backfill model
computation does not write database projections; exclusive directory creation
and atomic, no-replace artifact publication fence duplicate destination versions,
including direct worker invocations sharing the configured artifact root.

No graph refresh is added: the current PostgreSQL adjacency implementation has
no separate configured graph index requiring rebuilding. There is no implicit
Neo4j dependency.

## Reconciliation manifest

Use the Day 42 artifact-root and checksum-reference conventions. For scheduled
runs publish the reference envelope under `inbox/reconcile/<UTC interval end
YYYYMMDDTHHMMSSZ>.json`; its interval must exactly match the Airflow interval.
Missing envelopes fail closed. For manual runs pass `conf.manifest` as the
`{path, sha256}` reference to this document:

```json
{
  "schemaVersion": 1,
  "workflow": "reconcile",
  "intervalStart": "2026-10-14T03:00:00Z",
  "intervalEnd": "2026-10-15T03:00:00Z",
  "outputVersion": "repair-20261015-v1",
  "windowStart": "2026-09-15T03:00:00Z",
  "knowledgeCutoff": "2026-10-15T03:00:00Z"
}
```

The repair window is `(windowStart, intervalEnd]`, at most 90 days. The ingestion
watermark is between interval end and now. An explicit rolling window allows late
arrivals to be repaired on subsequent runs. Counters expire at read time; this job
does not decrement counters or delete events. Repairs retain events outside the
window and events newer than the supplied knowledge cutoff. Consequently the
result is a repaired **live** generation, not a historical model dataset.

Source precedence is retained authoritative PostgreSQL outbox, then consumer
journal, deduplicated by immutable event ID. Repeating repair with a new reviewed
output version creates a new generation with converged event membership, not
duplicate counts. Connection sequences prevent repeated cache-epoch effects.

## Historical backfill contract

Start from the Day 42 candidate manifest, set `workflow` to `backfill` and use a
new `outputVersion`. Keep pinned transform, evaluation/blend policies and context.
Each of at least three non-overlapping `history` entries additionally requires:

- `featureSourceRevision` and `labelSourceRevision`: exact decimal-string revisions
  from the retained v2 provenance, not today's database revision.
- `knowledgeCutoff`: the exact UTC `exportedAt` string from that provenance,
  including Java nanosecond precision when present.
- `light` and `heavy`: each an object containing `csv` and `provenance` checksum
  references to the retained v1 and v2 exports respectively.

The top-level `sourceRevision` remains the audited source revision of the original
candidate manifest; it is not compared with today's database. Per-export revisions
are the historical authorities. Feature times, label maturity, transform hashes,
CSV hashes and release IDs must match the pinned provenance. Each label horizon
must end no later than its knowledge cutoff, which must be within the manifest's
interval-end bound. Choose the backfill interval to include the retained export
knowledge times, not the later manual trigger time.

Important limitation: this replays a **retained export snapshot**, not an arbitrary
past ingestion-time query. The earlier exporter records source revisions and
export publication time, not bitemporal raw-profile history. `knowledgeCutoff`
therefore identifies that retained export's knowledge boundary; it must not be
changed to claim an earlier historical state. If the retained CSV, sidecar,
transform or context is unavailable, reconstruction is unsupported and the run
fails. Immutable historical dataset IDs/provenance are preserved in the copies.
Only new model/output versions are created. Reproducibility means identical
input bytes and lineage; it is not a cross-platform bitwise training guarantee.

## Operations and failures

Keep DAGs paused until dependencies, credentials and reviewed manifests are ready;
use the [Day 42 setup](DAY42_SCHEDULED_WORKFLOWS.md). No automatic retry, overwrite
or partial-work reuse is enabled. Inspect failed task and worker logs, preserve
receipts, then submit a reviewed new destination version if appropriate. Durable
resume/acknowledgement recovery remains Day 44.

For Kafka lag, poison records and DLT replay use the
[Day 35 runbook](DAY35_KAFKA_OPERATIONS.md), preserving event IDs and original
ordering. Do not delete consumer receipts, journals or outbox rows to make a
replay succeed. Receipt retention must exceed every permitted source/DLT replay
horizon; no automatic pruning is introduced here. If authoritative retained
sources are missing, repair cannot claim full recovery. See the
[Day 34 live/historical boundary](DAY34_LIVE_FEATURES.md).

## Acceptance

Run `mvn -B verify`, the trainer unittest suite, and:

```powershell
./scripts/day42-airflow-workflows-smoke.ps1 -IncludeDay43
```

The extended disposable-stack smoke runs Day 42 regressions, then authenticates
to the real scheduler for a complete historical backfill and candidate preparation,
with present-day labels deliberately changed. It verifies copied input hashes,
rejects an incorrect historical revision, repairs a missing live effect, repeats
repair to verify convergence, and checks unchanged registered historical datasets
and active serving pointer. Synthetic fixture history is explicitly not production
historical evidence. Existing PostgreSQL/Kafka tests cover late arrivals, expiry,
deduplication, rollback and concurrent generation swaps.

The small smoke cohort intentionally produces `qualityGate=not_met`; successful
candidate preparation is not permission to deploy a model. Acceptance requires
`automaticPromotion=false` and an unchanged active release. The live receipts
recorded repair execution 15/generation 2 and replay execution 16/generation 3,
with identical repaired event membership.

Verified 2026-10-16:

- 279 current-run Java tests, zero failures/errors/skips. Upstream modules passed
  in `artifacts/day43-verify.log`; API, Batch and datagen passed the resumed reactor
  in `artifacts/day43-verify-resumed.log` (`BUILD SUCCESS`). A stale earlier
  `KafkaComposeSmoke` report was excluded from this count.
- 106 trainer tests passed with warnings treated as errors:
  `artifacts/day43-python.log`.
- 20 offline Airflow tests passed in the packaged image without network access.
- Authenticated real-scheduler Days 41–43 acceptance passed:
  `artifacts/day43-airflow-acceptance.log`. Worker artifacts, contract logs, service
  logs and task-log archive are retained under
  `artifacts/day42-smoke/bce5da9d66/`. Disposable containers/volumes were removed.

The initial Java run was not a pass: its broker-restart test allowed only 30
seconds despite the logged Kafka consumer session timeout of 45 seconds. The
restart-only recovery assertion now permits 90 seconds for broker startup and
rebalance; normal delivery assertions remain 30 seconds. The seven real-Kafka
consumer integration tests and all remaining reactor modules passed on rerun.
No production timeout or delivery semantics were relaxed, and no failed test was
skipped or reported as successful.

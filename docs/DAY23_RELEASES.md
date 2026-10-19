# Day 23 — coherent embedding, feature and model releases

Day 23 is a real serving implementation, not a placeholder job. V7 preserves
the legacy tables and adds immutable release-scoped vectors/features, a frozen
transform registry, model bytes/metadata and one active pointer with an epoch.
Until first activation, the existing seed/Day 17/Day 19 workflow still works.

## Reproduce the complete demo

From the repository root, with Java 21, Maven, Docker and the Python environment
installed as described in `pymk-ml-training/README.md`:

```powershell
mvn verify
.\pymk-ml-training\.venv\Scripts\python -W error -m unittest discover -s pymk-ml-training/tests -v
.\scripts\day23-release-smoke.ps1
```

The smoke uses two uniquely named disposable containers, not your normal database.
It creates four members (including a blank profile), freezes one transform,
stages three historical snapshots plus a serving snapshot, exports registered
labels, trains real logistic regression and activates the resulting model.
It checks HTTP recommendations, cached responses, database-only model startup,
rollback to legacy and back, and three distinct Redis epoch namespaces. The
fixture requires validation AUC >= 0.9; this tiny separable cohort is a correctness
check, **not evidence of real-world recommendation quality**. Logs, exports and
artifacts remain under ignored `artifacts/day23-smoke/<run>/`; only its containers
and API process are stopped afterward.

## Manual operator workflow

Examples use PowerShell from the root. Batch defaults to local `pymk` PostgreSQL;
override `--spring.datasource.url`, username and password for another database.
All dates and paths below are examples; use your own stable source snapshot and
wait until label windows mature. Do not create historical vectors from profiles
that were not known then.

1. Generate vectors with the Day 22 CLI. Fit the transform at or before the first
   training snapshot; reuse that exact artifact without `--fit` for every later
   snapshot. Create a different immutable release ID for each snapshot.
2. Build the staging manifest and materialize the release:

```powershell
.\pymk-ml-training\.venv\Scripts\python -m pymk_training.release_manifest --release-id profile-live-v1 --transform artifacts/transform.json --vectors artifacts/vectors.csv --output artifacts/profile-live-v1.json --min-validation-auc 0.7
java -jar pymk-batch/target/pymk-batch.jar --spring.batch.job.name=embeddingRefreshJob manifestPath=artifacts/profile-live-v1.json
```

The manifest binds raw file SHA-256 hashes, embedding version/fit cutoff,
snapshot and candidate budgets. Defaults match the batch's per-source limits
(graph 2000, heuristic 1000, embedding 1000); custom limits must match batch
configuration and cover online limits. Staging materializes the full bounded
source union, before the online global cap, to avoid incomplete L1 coverage.
Blank zero vectors count toward exact member coverage but never enter cosine ANN.

3. Export each completed historical release with a mature future label window:

```powershell
java -jar pymk-batch/target/pymk-batch.jar --spring.batch.job.name=servingReleaseJob action=export releaseId=profile-history-1 labelWindowEnd=2026-01-10T00:00:00Z outputPath=artifacts/train-1.csv
```

This creates CSV plus `.csv.provenance.json` and registers its hash in PostgreSQL.
Repeat for at least three temporally separated snapshots, with both classes in
each split. Training and validation label horizons must finish before the next
partition starts. Published paths cannot be overwritten. CSV and sidecar are
individually atomic, not one filesystem/database transaction: on publication or
commit failure use a new output path; incomplete/unregistered exports cannot be
promoted. Retain both files for training.

4. Train with the exact frozen transform:

```powershell
.\pymk-ml-training\.venv\Scripts\python -m pymk_training.train_light_ranker --input artifacts/train-1.csv --input artifacts/train-2.csv --input artifacts/train-3.csv --embedding-transform artifacts/transform.json --model-version profile-l1-v1 --output-dir artifacts/models/profile-l1-v1
```

The trainer verifies CSV/sidecar/transform hashes, fits preprocessing on training
data only, and records compatibility plus measured validation metrics. Promotion
checks registered input hashes, mature label horizons, validation AUC, feature
contract, checksum and actual ONNX inference on staged feature samples.

5. Inspect `SELECT release_id, epoch FROM pymk_active_release;`, then activate
   using that exact epoch. Supply the **currently deployed** legacy model for
   first activation so rollback has its original artifacts:

```powershell
java -jar pymk-batch/target/pymk-batch.jar --spring.batch.job.name=servingReleaseJob action=activate releaseId=profile-live-v1 expectedEpoch=0 modelPath=artifacts/models/profile-l1-v1/profile-l1-v1.onnx metadataPath=artifacts/models/profile-l1-v1/profile-l1-v1.metadata.json legacyModelPath=artifacts/models/light-ranker/light-logreg-v1.onnx legacyMetadataPath=artifacts/models/light-ranker/light-logreg-v1.metadata.json
```

No API restart is required. Managed releases load model bytes from PostgreSQL;
local legacy model paths are needed only before any model has been captured.
The legacy cache model-version setting must match local metadata before first
activation; thereafter the pinned release's model version is authoritative.
Release ID and epoch extend both existing candidate/result cache namespaces.

6. Roll back with the new epoch (or select a previously validated READY ID):

```powershell
java -jar pymk-batch/target/pymk-batch.jar --spring.batch.job.name=servingReleaseJob action=rollback releaseId=legacy expectedEpoch=1
```

Each switch increments the epoch, including switching back to an old release.
Old Redis entries expire by TTL and cannot be read under the new epoch.
Previously READY artifacts are immutable; use a new release/model version for
changes. Stale concurrent promotion/rollback attempts fail without moving the
pointer. Give repeated operator jobs fresh `run.id` values when their entire
parameter set would otherwise identify an already-completed Spring Batch job.

## Restart, safety and current limits

- Restart a failed refresh with **identical parameters and unchanged files**.
  Import chunks and per-member feature checkpoints commit transactionally.
  Completed imports are skipped on a new deterministic rerun. Source mutation
  invalidates unfinished staging/promotion: choose a new release ID and rebuild.
- Source revision triggers cover members, connections and events. They serialize
  writers through a revision row; SHARE locks cover each feature chunk, export
  and promotion validation. This conservative stable-data design has contention
  costs and is not a high-throughput ingestion architecture. M6/M7 will extend it.
- Activation requires the staged cohort to cover all live members and edges;
  older historical training releases normally cannot be activated. Historical
  profile reconstruction and ingestion-time provenance are not implemented.
- Requests pin the active pointer with a shared transaction advisory lock;
  promotion/rollback take the exclusive lock. Default request admission is 2,
  with a five-second admission wait. JDBC capacity must cover admitted requests,
  `min(L0 maxConcurrency, 3 * requestConcurrency)` workers, and one spare connection.
  Startup rejects an undersized pool. The default Hikari pool of 10 is sufficient.
- Graph changes after publication can add pairs outside materialized coverage.
  Those requests still fail closed with 503 until a new release covers them.
  Rollback restores an artifact generation, **not historical graph/member state**.
  Graph-mutation cache invalidation remains eventual, separate from atomic
  release switching. No zero-downtime continuous-data guarantee is claimed.
- The Day 17 writer is blocked during managed serving. Legacy tables remain
  available for rollback; do not mutate them if you need that exact baseline.
- Model inference/eviction is synchronized with at most two ONNX sessions.
  This bounds resources and prevents close-during-inference races, but throughput
  and the target p99 latency still require M8 measurement.
- No automatic scheduling or garbage collection yet. Retain active, historical
  training and rollback generations, registry rows and artifacts. Database backups
  must include all V7 tables. The destructive seed reset explicitly resets the
  release registry and increments the epoch; it is not an operational rollback.

Automated Java coverage exercises missing coverage, invalid hashes/model quality,
source drift, chunk interruption/restart, deterministic reruns, stale epochs,
durable model loading, rollback and publication waiting for pinned readers.
Python tests exercise immutable manifests and training provenance. Passing these
checks is evidence for Day 23, not a promise that all possible bugs are absent.

## Verification record — 2026-10-03

- `mvn verify`: all 12 reactor modules successful; 124 tests, zero failures,
  errors or skips.
- Python unittest discovery with `-W error`: 21 tests passed.
- `docker compose -f infra/docker-compose.yml config --quiet`: passed.
- Isolated smoke run `0654214d44`: passed actual fitting/training, four staged
  releases, registered exports, model activation, database-only model startup,
  HTTP probability ordering, cache reuse and rollback through epochs 1, 2 and 3.
  The tiny fixture's validation AUC was 1.0 on six rows; no production-quality
  or performance claim follows from this result.
- Temporary smoke containers and API process were stopped; generated evidence
  remains in the ignored artifacts directory. The normal local database was untouched.

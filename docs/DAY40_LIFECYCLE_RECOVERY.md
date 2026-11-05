# Day 40 — MLflow lifecycle recovery

MLflow is a control-plane dependency, never a recommendation-request dependency.
Already validated L1 serving loads retained database bytes; L2 uses retained local
files. A registry or artifact outage blocks **new synchronization/publication**,
not current inference. An already synchronized, approved bundle may still be
activated offline: approval revalidates local bytes, database provenance and epoch,
not remote service availability. Registration and successful restore are not approval.

## Recovery guarantees and limits

| Failure | Behavior and recovery |
|---|---|
| Tracking API unavailable | Publication/synchronization fails; acknowledged local candidate remains unchanged. Restore service, retry publication or sync into a new directory. |
| Artifact storage unavailable, tracking healthy | No `sync.json` is published. Restore artifact access; retry into a fresh directory. |
| Corrupted download or source manifest changed during download | Checksum failure; no completed candidate. Restore authoritative bytes, never edit expected digests to accept corruption. |
| Lost publication acknowledgment | Reuse the existing local spool/stage. Acknowledged repeat verifies exact remote bytes and creates no version. A lost version-creation acknowledgment can leave an orphan version; exactly-once registration is not claimed. |
| Concurrent approvals/activation | PostgreSQL transaction lock and expected epoch allow one winner, one audit record. The loser must re-read state and obtain fresh approval, not blindly retry with a new epoch. |
| Missing/corrupt rollback model | Rollback fails before pointer/epoch changes. Retained model bytes, metadata, model version and schema-2 registry pins are rechecked with actual Java inference. Repair the rollback target; current valid serving stays selected. |

An existing `READY` flag alone is no longer enough for rollback. This protects
against loss/corruption of rollback model artifacts, not arbitrary database
tampering. Retain the complete feature/vector generation and transform too.
L2/process configuration still requires the drained restart boundary documented
in [Day 39](DAY39_GUARDED_PROMOTION.md); database rollback does not hot-swap L2.

## Runnable isolated lifecycle demo

Prerequisites: Java 21, Maven, Python training environment with `[heavy,tracking]`,
PowerShell 7 and Docker. From the repository root:

```powershell
mvn -B -ntp verify
./pymk-ml-training/.venv/Scripts/python.exe -W error -m unittest discover -s pymk-ml-training/tests -v
./scripts/day40-lifecycle-smoke.ps1
```

On Linux/CI pass `-Python python`. `-SkipBuild` reuses the already built pinned
MLflow image, not an unbuilt Java JAR. This is a **disposable fixture demo**:

1. Freeze deterministic synthetic temporal datasets and a known transform; retain
   copies under `artifacts/day40-smoke/<run>/frozen-inputs`.
2. Train actual L1/L2 ONNX models, track training/evaluation/blend evidence, register
   exact bytes and run packaged Java parity. Exercise interrupted upload/retry and
   alias movement. The tiny evaluation's failed gate remains failed; packaged
   approval rejects its insufficient requester cohort.
3. Make one exact artifact unreadable while the tracking API remains healthy;
   prove failure and recovery. Stop tracking and prove fail-closed sync/publication.
4. While writers are stopped, back up metadata and **all** artifact bytes. Seal a
   SHA-256 inventory, verify it, and restore into a separate project's fresh volumes.
5. Reuse the original tracking URI, reproduce pinned L1/L2 and evaluation/blend
   bytes, verify original run/version identities and no-op acknowledged retries.
   Deliberately corrupt a restored artifact and verify rejection.

The script captures per-phase/service logs and preserves local backup evidence.
Its `finally` cleanup removes only the two uniquely named disposable projects and
their volumes. It never stops, migrates or deletes your normal deployment.
The fixture is not production-quality approval evidence or a performance benchmark.
Random run IDs/output directories do not change the fixed training seed; bytewise
recovery means restoring registered bytes, not assuming retraining under different
runtime versions produces identical ONNX serialization.

### Train from the retained frozen data

Choose an actual completed demo directory and a **new** output directory. Activate
the training virtualenv, then replace `<run>` below. The trainer uses its fixed
seed; no new dataset export or relabeling is performed:

```powershell
python -m pymk_training.train_light_ranker --input artifacts/day40-smoke/<run>/frozen-inputs/l1-0.csv --input artifacts/day40-smoke/<run>/frozen-inputs/l1-1.csv --input artifacts/day40-smoke/<run>/frozen-inputs/l1-2.csv --embedding-transform artifacts/day40-smoke/<run>/frozen-inputs/transform.json --model-version day40-replay --output-dir artifacts/day40-replay/models --tracking-dir artifacts/day40-replay/tracking
```

This writes an offline tracking bundle. Start your normal optional MLflow profile
using [Day 36](DAY36_MLFLOW_INFRASTRUCTURE.md), then publish the **printed** bundle:

```powershell
python -m pymk_training.tracking --bundle <printed-bundle-path> --tracking-uri http://127.0.0.1:5000
python -m pymk_training.registry prepare --bundle <printed-bundle-path> --output-dir artifacts/day40-replay/candidate --java-verifier-jar pymk-batch/target/pymk-batch.jar
python -m pymk_training.registry publish --stage artifacts/day40-replay/candidate --tracking-uri http://127.0.0.1:5000
```

This is a tracked, registered **candidate**, not a serving promotion. Training or
publication failure returns nonzero; keep its frozen outputs/spool for diagnosis.
Do not use `--require-quality-gate` failure as a reason to reduce thresholds.

## Full operator workflow

Use [Day 37](DAY37_EXPERIMENT_TRACKING.md) for tracking/publication recovery,
[Day 27](DAY27_EVALUATION.md) for comparison,
and the evaluator CLI below for explicit frozen comparison. Replace all paths
with the corresponding immutable dataset/model/policy artifacts:

```powershell
python -m pymk_training.evaluate_heavy_ranker --input <frozen-v2.csv> --model <heavy.onnx> --metadata <heavy.metadata.json> --l1-model <light.onnx> --l1-metadata <light.metadata.json> --policy pymk-ml-training/evaluation-policy.json --output artifacts/comparison.json --tracking-dir artifacts/comparison-tracking --require-quality-gate
```

Repeat `--input` for the frozen temporal snapshots. Retain a `not_met` report and
nonzero quality-gate exit as valid evidence of a rejected candidate. The evaluator
does not activate serving. Publish its tracking bundle, then use `registry prepare
--lineage-bundle <evaluation-bundle>` (and the blend bundle if applicable) to bind
comparison evidence. [Day 38](DAY38_MODEL_REGISTRY.md) documents registration and
`extend-release`; [Day 39](DAY39_GUARDED_PROMOTION.md) gives exact synchronization,
rehearsal, approval and `activate-approved` commands. Approval remains a trusted
local-operator action with reviewed measurements, never a signature or remote attestation.

## Coordinated backup and restore procedure

The executable reference is `scripts/day40-lifecycle-smoke.ps1`. Do not point its
destructive restore operations at your live metadata database.

1. Freeze registry/tracking writes, including external clients. Stop the tracking
   server; quiescing just one client is insufficient in a shared deployment.
2. Run `pg_dump -U mlflow_admin -d mlflow -Fc -f /tmp/metadata.dump` in the
   dedicated metadata DB container. Copy that dump and the stopped server's
   `/mlartifacts` tree to protected backup storage. Copy **all** artifact files,
   including source training/evaluation manifests, not only the active ONNX file.
3. Record exact relative filenames and SHA-256 for the dump and every artifact
   (`backup-manifest.json` in the rehearsal). Reject missing, changed or extra
   files before restore. Keep the manifest in access-controlled storage with the
   backup; checksums detect corruption, not a malicious rewrite of both.
4. Restore to **fresh, isolated** volumes using the same pinned MLflow/PostgreSQL
   images and configured roles. The script uses `pg_restore --role=mlflow
   --clean --if-exists --no-owner --exit-on-error` only in that disposable database,
   restores the tree as the non-root artifact owner, then runs metadata migration.
5. Reassign the original tracking endpoint after the old server is stopped.
   Source publication binds the tracking destination identity; changing the URI
   is not a transparent restore. An endpoint migration needs a separately reviewed
   republishing plan, not editing recorded hashes or aliases.
6. Verify health, source run/version IDs, exact pins and Java parity via a new
   `model_sync` output directory. Do not resume publication or promotion until
   verification succeeds. Keep original storage until recovery is accepted.

The backup covers MLflow metadata/artifacts. It is **not** a PYMK database backup,
raw-dataset archive or fleet deployment backup. Preserve those separately at a
consistent release boundary. Restore does not automatically activate the candidate.

## Serving rollback and retention

Drain traffic and select the retained L1-only process configuration first. With
the actual current epoch and previously READY release ID (or captured `legacy`):

```powershell
java -jar pymk-batch/target/pymk-batch.jar --spring.batch.job.name=servingReleaseJob action=rollback releaseId=<retained-release-id-or-legacy> expectedEpoch=<current-epoch>
java -jar pymk-api/target/pymk-api.jar --spring.config.additional-location=file:<retained-stage>/rollback.properties
```

Check health, scorer header, recommendations and updated epoch before reopening
traffic. If the rollback target is missing/corrupt, stop and recover it from a
verified backup; do not delete the current release or bypass validation. The
packaged Day 23 smoke exercises actual HTTP serving and both rollback directions:

```powershell
./scripts/day23-release-smoke.ps1 -IncludeObservability
```

**Day 40 retention policy is retain-all, with no automated garbage collection.**
Protect active and rollback releases plus their transitive lineage:

- PYMK database model bytes/metadata, frozen features/vectors/transforms, dataset
  registrations, release manifests, approval audit and active epoch;
- numeric registry versions, their upload runs, original source tracking runs,
  all candidate files, evaluation policies/reports and blend configurations;
- synchronized deployment and fallback directories, production verifier/build
  identity, frozen raw CSVs/sidecars and private local tracking spools;
- paired metadata/artifact backups and their inventory/restore evidence.

Do not run MLflow garbage collection, delete versions/runs/volumes, or apply
artifact age-based lifecycle expiration to these stores. Alias removal, an old
timestamp or a failed evaluation does not prove an artifact is unreferenced.
All candidates are retained conservatively; any future pruning tool must compute
the transitive protected set and demonstrate recovery before deletion. This costs
storage and is intentional. Disposable smoke-project cleanup is the only exception.

## Acceptance record

The Day 40 integration tests cover real PostgreSQL competing promotions (one
winner/audit), cold model startup without local source files, rollback after
promotion, absent/corrupt rollback models and altered registry metadata pins.
Python tests cover outages, incomplete sync, changed source manifests and backup
inventory/identity corruption. The live rehearsal includes the full Days 38–39
registry demo plus actual HTTP artifact failure and fresh-volume restore.

Verified on **2026-10-15**:

- Java: full `mvn -B -ntp verify` **BUILD SUCCESS**, **275 tests**, zero failures,
  errors or skips (`artifacts/day40-verify-final.log`, completed 09:29 Cairo).
- Python: **106 tests passed** with warnings treated as errors; `pip check`
  reported no broken requirements (`artifacts/day40-python-final.log`).
- Live lifecycle rehearsal: all six phases passed, including frozen-data replay,
  artifact-only outage, tracking outage, verified coordinated backup, fresh-volume
  restore, exact version/lineage reproduction and corrupted-download rejection.
  Evidence: `artifacts/day40-lifecycle-retry.log` and
  `artifacts/day40-smoke/0dfdc124b1/`. Disposable volumes were cleaned up;
  local backup files, frozen inputs and logs remain available.
- Packaged serving: `day23-release-smoke.ps1 -IncludeObservability` exited **0**.
  Real training/activation, database-model startup, HTTP probabilities/cache,
  both rollback directions, broker outage/recovery, independent consumers,
  reconciliation, cache invalidation and zero-lag monitoring after restart passed.
  Evidence: `artifacts/day40-serving-smoke.log` and
  `artifacts/day23-smoke/d6419a58eb/`. The disposable API, database, Redis and Kafka
  resources were cleaned up. This run uses L1; the full Java suite also covers
  heavy-ranker HTTP serving. No new L2/blend performance benchmark is claimed.

An earlier full Java run and concurrent demo were interrupted by disappearing
Docker services (database/Kafka connection failures and a lost MLflow connection).
Those runs are **not** accepted as passes. The successful reruns are recorded
above. These tests are bounded evidence, not a guarantee
that every possible production fault is absent.

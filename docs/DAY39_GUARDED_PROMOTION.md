# Day 39 — Guarded promotion and model synchronization

## What runs, and what remains an operator responsibility

`pymk_training.model_sync` downloads **numeric pinned versions**, verifies every
artifact and original tracking source, and reruns production Java parity. It
never reads an alias or changes the active database pointer. Partial downloads
have no `sync.json`; retry into a fresh directory. Existing serving remains
independent of MLflow availability.

`PromotionApproval` validates a synchronized bundle and reviewed rehearsal
evidence, then exclusively creates `approval.json`. The explicit
`activate-approved` batch action revalidates this approval under the existing
expected-epoch database lock. It retains all existing source-fingerprint,
coverage, temporal-provenance, quality and production-model checks. Flyway V12
records the approval and activated epoch atomically with the pointer switch.
All L2 label windows, including test outcomes used for selection, must be mature
at the serving snapshot. Activation verifies L2 CSV IDs, hashes and full
provenance against the database's registered v2 datasets.
Legacy schema-1 activation and rollback remain supported; schema-2 cannot bypass
approval using the old `activate` action.

This is a **local trusted-operator workflow**, not a cryptographic signature,
authenticated approval API or autonomous deployment controller. Restrict write
access to staged bundles, evidence and batch credentials. The operator supplies
and reviews measured cohort/coverage/latency and rollback evidence from an
isolated production-like rehearsal. The software checks bindings, freshness,
thresholds and model contracts; it cannot prove an operator's measurements are
truthful. Do not substitute fixture numbers or a tiny smoke cohort for evidence.

L2/blend remain process configuration. A pointer change cannot update a running
JVM's L2 model. Use a drained deployment boundary, not an alias flip or an
uncoordinated rolling deployment. A multi-host rollout controller and signed
approvals are outside this local portfolio scope.

## 1. Synchronize and stage

Build with `mvn -B -ntp verify`. Register and extend a fresh release using the
[Day 38 workflow](DAY38_MODEL_REGISTRY.md), then:

```powershell
python -m pymk_training.model_sync --release artifacts/release-v2.json --output-dir artifacts/deployments/release-v2 --tracking-uri http://127.0.0.1:5000 --java-verifier-jar pymk-batch/target/pymk-batch.jar
java -jar pymk-batch/target/pymk-batch.jar --spring.batch.job.name=embeddingRefreshJob manifestPath=artifacts/deployments/release-v2/release.json
```

Supply your normal database configuration. The relocated manifest has absolute
paths to copied vectors/transform and retains registry references and candidate
budgets. Stage **this** manifest, not the original: its exact checksum is the
approval identity. Use a new release ID if an earlier manifest was already staged.
Staging validates actual database cohort coverage and does not activate anything.

Keep the synchronized directory at its final path. Registration, download and
staging are all safe to perform while the current release serves traffic.

## 2. Rehearse and freeze process settings

Create `deployment.properties` in that directory. For L1:

```properties
pymk.serving.ranker=L1
pymk.heavy-ranker.enabled=false
pymk.light-ranker.max-candidates=500
pymk.heavy-ranker.max-candidates=500
```

For L2/RERANK, enable the heavy scorer and set its model/metadata paths to the
absolute synchronized `heavy/model.onnx` and `heavy/metadata.json` paths (use
forward slashes in Java properties). L2 requires a pinned `evaluate.json` with
quality gate `met`. RERANK additionally requires all properties from the pinned
`heavy/blend.properties` to match exactly, including survivor budgets. Merely
registering a failed evaluation cannot satisfy this gate.

Create `rollback.properties` containing the explicit L1-only settings above.
Retain the previously READY release or captured legacy baseline and rehearse
switching back to it. These files may contain only appropriate deployment
settings, not database passwords. Protect them from conflicting environment or
command-line overrides when launching the API.

Run the candidate on an isolated copy of the required database/source snapshot
with its pinned process configuration. Use the existing schema-1 rehearsal activation path on that disposable
database, with the same model/vector/transform bytes and budgets; this is not
permission to downgrade the production registry manifest. Measure cold-cache and warm-cache HTTP
p95 separately using at least 30 independent requesters; document cache reset,
load, concurrency, hardware, sample size and cohort in retained rehearsal logs.
Cold means deliberately empty candidate caches, not merely a new HTTP connection.
Check response probabilities, scorer header, coverage and rollback behavior.
The existing full smoke demonstrates process restarts/L1 fallback, but its tiny
dataset is **not** a production-quality rehearsal or promotion approval.

Record `evidence.json` using measured values, not the illustrative placeholders:

```json
{
  "schemaVersion": 1,
  "manifestSha256": "<SHA-256 of synchronized release.json>",
  "measuredAt": "<UTC instant>",
  "environment": "<isolated deployment and retained measurement-log location>",
  "cohortSha256": "<SHA-256 of the frozen requester/cohort evidence>",
  "mode": "L1",
  "coverage": {"observed": 1.0, "minimum": 1.0},
  "quality": {"observed": 0.8, "minimum": 0.75},
  "requesters": {"observed": 100, "minimum": 30},
  "coldP95Ms": {"observed": 150, "maximum": 200},
  "warmP95Ms": {"observed": 20, "maximum": 50},
  "temporalValid": true,
  "rollbackVerified": true,
  "deployment.propertiesSha256": "<exact deployment-file SHA-256>",
  "rollback.propertiesSha256": "<exact fallback-file SHA-256>"
}
```

For L1, `quality.observed` must equal the pinned metadata's validation ROC AUC.
For L2/RERANK, the quality observation must equal the pinned evaluator's test
`exportedPool.heavyAccepted.rocAuc`, in addition to its required `met` result.
Set thresholds **before** reviewing
candidate outcomes; never lower them simply to obtain approval. Evidence expires
after 24 hours. Coverage must be complete; missing/NaN/failed measurements reject.
The cohort digest and environment locate the operator's retained evidence; they
are not an automated remote audit of that environment.

## 3. Approve, drain, activate, restart

Read the current `pymk_active_release.epoch` and preserve its rollback identity.
Replace the example epoch/expiry/operator with actual values:

```powershell
$pymkApprovalExpiry = (Get-Date).ToUniversalTime().AddHours(1).ToString('o')
java '-Dloader.main=com.ahdyahmed.pymk.batch.PromotionApproval' -cp pymk-batch/target/pymk-batch.jar org.springframework.boot.loader.launch.PropertiesLauncher artifacts/deployments/release-v2 artifacts/deployments/release-v2/evidence.json operator-name 0 $pymkApprovalExpiry
```

Approval expires within 24 hours and binds the exact synchronized manifest,
inventory, process configuration and expected epoch. Existing approvals cannot
be overwritten. Failed or stale approval requires renewed evidence and an
explicit new approval; do not edit digests to bypass validation.
To renew approval for unchanged staged bytes, first archive the old `approval.json`
outside the stage, preserving its bytes and digest, then invoke the approval
command with fresh evidence/epoch/expiry. Do this only while no activation is in
progress. Changed models, manifests or process settings require a new candidate
and rehearsal, not an in-place rewrite of an approved deployment.

Drain ingress and stop old API workers. While drained:

```powershell
java -jar pymk-batch/target/pymk-batch.jar --spring.batch.job.name=servingReleaseJob action=activate-approved releaseId=release-v2 expectedEpoch=0 stagePath=artifacts/deployments/release-v2 legacyModelPath=artifacts/models/light-ranker/light-logreg-v1.onnx legacyMetadataPath=artifacts/models/light-ranker/light-logreg-v1.metadata.json
java -jar pymk-api/target/pymk-api.jar --spring.config.additional-location=file:artifacts/deployments/release-v2/deployment.properties
```

Use the manifest's actual release ID and compatible legacy baseline paths. Verify
health, the active database release/epoch, HTTP scorer header and representative
recommendations before reopening ingress. No approval file automatically drains
traffic or proves that a later process used the intended settings.

If readiness fails, keep ingress drained, stop candidate workers, run the existing
`servingReleaseJob action=rollback releaseId=<previous READY ID or legacy>
expectedEpoch=<current epoch>`, and restart using `rollback.properties`. Verify
L1 readiness before reopening. Never reuse a stale epoch. Pointer changes retain
release-aware cache isolation; rollback uses previously validated database bytes.

## Validation and retention

```powershell
python -W error -m unittest discover -s pymk-ml-training/tests
mvn -B -ntp verify
./scripts/day38-registry-smoke.ps1
./scripts/day23-release-smoke.ps1 -IncludeObservability -IncludeBlendTuning
```

The registry smoke now also downloads both pinned models after an alias moves,
reruns Java validation and rejects corrupted downloads without a completion record.
PostgreSQL integration tests exercise approved activation, atomic audit, stale
epoch/approval rejection, expiry, changed configuration, failed numeric gates and
L1 rollback. Java test evidence is explicitly synthetic validation-fixture data,
not production-quality model approval. Retain synchronized artifacts, manifests,
approval, deployment/fallback files, measurement logs and database audit together.
Never prune artifacts needed by current or rollback releases. Day 40 extends
backup/restore and lifecycle recovery coverage.

## Acceptance record — 2026-10-15

- Final full Maven reactor: **272 tests, zero failures/errors/skips**, `BUILD SUCCESS`.
  The initial run exposed an upgrade-test count assumption after adding V12;
  the test now verifies V11 and V12 separately and preserves existing journals.
  Approval integration tests use the application's fixed test clock.
- Final Python suite: **99 tests passed**, warnings treated as errors; dependency
  check reported no broken requirements.
- Real MLflow test exited zero: exact L1/L2 synchronization, production Java
  parity, alias isolation, corrupted-download rejection, and packaged approval
  rejection of a tiny cohort. Evidence: `artifacts/day38-smoke/be0fd53cf2/`.
- Combined packaged run passed v2 exports, heavy training, frozen evaluation,
  Python/Java parity, public L2 scoring, incompatible-release rejection, L1
  rollback, re-ranking, caching and tuned configuration/re-enable assertions.
  Its final consumer recovery check was interrupted when Docker stop signals
  reached PostgreSQL, Redis and Kafka together. Docker events do not identify
  the initiator. This run is **not** recorded as an overall successful command.
  Evidence: `artifacts/day23-smoke/3736dfe24b/`.
- A fresh `-IncludeObservability` packaged run exited zero and completed real
  training, activation, database-model startup, HTTP probabilities, caching,
  two rollbacks, Kafka outage/recovery, independent consumer deduplication,
  reconciliation, live counters, cache invalidation and monitoring recovery after
  API restart. Evidence: `artifacts/day23-smoke/3145a73a1a/`.

Logs: `artifacts/day39-verify-final.log`, `artifacts/day39-python-final.log`,
`artifacts/day39-registry-smoke.log`, `artifacts/day39-serving-smoke.log` and
`artifacts/day39-recovery-smoke.log`. Generated evidence is ignored by Git.
Disposable services were cleaned up; no production model was promoted.
These checks cover the documented scenarios, not every possible defect or a
production-quality model approval. Day 40 remains future work.

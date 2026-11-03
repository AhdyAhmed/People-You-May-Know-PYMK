# Day 38 — Immutable artifacts and model registry contracts

## Boundary

The registry adapter stages exact L1/L2 ONNX bytes, validates their contracts using
Python and the production Java model loaders, uploads checksum-bound bundles and
registers exact MLflow versions. It never converts/re-exports a model, changes an
alias, calls a serving activation API or updates the database release pointer.

Schema-1 Day 23–30 release manifests remain usable. Schema-2 manifests add pinned
registry evidence and can stage embeddings, but **activation is intentionally
blocked** until the guarded model-sync/approval workflow in Day 39 is implemented.
Successful registration is not model quality approval. A failed offline quality
gate remains failed evidence; it does not become permission to promote.

## Prerequisites and commands

Build the Java reactor and install the optional training/tracking dependencies:

```powershell
mvn -B -ntp verify
python -m pip install -e 'pymk-ml-training[heavy,tracking]'
```

Use the training virtualenv's Python. Start the optional MLflow service using the
[Day 36 infrastructure runbook](DAY36_MLFLOW_INFRASTRUCTURE.md). Train using
[Day 37 tracking](DAY37_EXPERIMENT_TRACKING.md) with `--embedding-transform` and
publish the tracking evidence first. Legacy models without a frozen transform
cannot enter the registry candidate workflow.

Prepare a fresh directory from an acknowledged training bundle (replace paths):

```powershell
python -m pymk_training.registry prepare --bundle artifacts/tracking/<logical-id>/<attempt-id> --output-dir artifacts/candidates/light-v1 --java-verifier-jar pymk-batch/target/pymk-batch.jar
```

Use `--lineage-bundle` repeatedly to attach acknowledged evaluation and blend
tracking bundles. These reports must refer to the exact L1/L2 model and metadata
digests. A blend configuration must match its report's checksum. Their tracking
destination must match the training source. No inference from filenames or
mutable aliases substitutes for these checks.

Publish the staged candidate:

```powershell
python -m pymk_training.registry publish --stage artifacts/candidates/light-v1 --tracking-uri http://127.0.0.1:5000
```

The installed equivalent command is `pymk-model-registry`. Use the same commands
for an L2 training bundle in a separate stage directory. No raw training CSVs are
uploaded: `dataset.json` records the dataset hashes, provenance and aggregate
evidence; actual datasets remain separately retained/access-controlled.

Extend a fresh, not-yet-staged release manifest without overwriting it:

```powershell
python -m pymk_training.registry extend-release --release artifacts/release-v1.json --stage artifacts/candidates/light-v1 --stage artifacts/candidates/heavy-v1 --output artifacts/release-v2.json
```

L1 is required; the second L2 stage is optional. The extension preserves release
identity, timestamps, candidate limits, quality threshold and transform/vector
checksums. Existing relative artifact paths become absolute so moving the new
manifest does not change their meaning. The transforms must match; L2 must bind
the exact registered comparison L1 ONNX and metadata bytes. The new manifest is
created exclusively and cannot overwrite an earlier candidate.
If the original release ID is already staged/active in PostgreSQL, build a new
schema-1 candidate with a new release ID first. Existing database manifests remain
immutable; an extension is not an in-place upgrade of an active release.

## Validation and evidence

`prepare` verifies acknowledged, unchanged Day 37 output bindings and copies the
original ONNX/metadata/transform bytes into a new directory. The dataset descriptor
and source tracking manifest accompany them. Optional full evaluation/blend
reports, tracking evidence and configuration are included as lineage, not as
independently interchangeable serving models.

Python rejects external ONNX weight references and checks the model checksum, features, tensor input, finite probability
outputs and L1 learned-parameter parity. L2 must retain its checksum-bound training
parity evidence. A deterministic 64-row synthetic fixture contains no member rows.
The packaged `RegistryModelVerifier` loads the same bytes with the actual Java L1
or L2 loader, checks batch and singleton scoring against Python within `1e-6`,
and writes checksum-bound validation evidence. It uses Spring Boot's
`PropertiesLauncher` without starting Spring, a database or a network client.
The candidate records the verifier JAR digest as build identity.

`candidate.json` inventories every file and its SHA-256, model/feature/embedding
compatibility, original tracking run IDs and L2's comparison L1 identity. A
separate `registry-state.json` records publication attempts. A failed preparation
does not produce a publishable candidate; use a fresh directory after diagnosing
the failure. Do not manually edit hashes or manufacture validation evidence.
Candidate and acknowledgment digests use SHA-256 of sorted-key, compact JSON
(`tracking.canonical`); inventory entries hash exact file bytes. This distinction
allows deterministic manifest identity without treating formatting as model bytes.

Publication revalidates local artifacts and the original remote tracking manifests.
Each upload attempt gets a fresh run in `pymk.registry.v1` with a content-addressed
`candidate/<digest>/` path. Every uploaded artifact is downloaded and verified
before a model version is created. The version must become `READY`; completion
tags and terminal status are acknowledged before local publication is recorded.
Publication tags are synchronous even when the SDK's async logging is enabled.

Registry names are `pymk.light.<modelVersion>` and `pymk.heavy.<modelVersion>`.
Creating the registered-model resource atomically claims the ONNX+metadata byte
identity for that application model version. Reusing that name with different
model **or metadata** bytes fails closed, including repeated training that changes
only metadata timestamps. Choose a new application model version for new bytes.
Additional lineage for identical model/metadata bytes can form another exact
MLflow version under the same name. No registry tags are rewritten to bypass a
conflict. Permissions must prevent unrelated writers from changing these claims.

`registered.json` records the exact name/version, registry upload run ID, source
URI, `models:/<name>/<numeric-version>` URI, candidate/file digests, source tracking
run IDs and compatibility. It is checksum-bound by the local acknowledgment.
The release extension embeds a copy of this pinned reference. Aliases are optional
human discovery labels; this adapter never reads or changes them. Deprecated
registry stages are not used. The bundle is raw ONNX plus evidence, not an MLflow
pyfunc deployment; MLflow model serving is outside the architecture.

## Failure, retention and privacy

- A failed command exits nonzero. An interrupted upload leaves no new model
  version. Retrying the same stage uses a new run and preserves original bytes.
- Lost acknowledgment **after** version creation can leave an orphan/duplicate
  exact version. Local state stays unpublished; retry can create another exact
  version. This is not exactly-once registration. Retain attempt IDs, run IDs,
  candidate hashes and server logs to audit duplicates; never infer approval from
  registry presence alone.
- Acknowledged retries verify the pinned version, remote run completion and all
  artifact checksums, then return the same reference without creating a version.
- A local `.publish.lock` prevents concurrent publishers of one stage. Remove a
  stale lock only after confirming its process stopped. Do not reuse an incomplete
  preparation directory, alter the destination or patch a candidate's checksums.
- Immutability is enforced by application contracts and read-back, **not** by an
  MLflow write-once storage guarantee. Administrators can mutate remote objects;
  subsequent verification detects corruption and fails closed. A local published
  checkpoint is historical acknowledgment, not proof of current remote health.
  Model synchronization must revalidate every pinned artifact before use.
- Retain the stage, checksum-matched verifier JAR/build inputs, original datasets,
  schema-1 and schema-2 manifests, and paired
  registry metadata/artifact backups. Never delete an active/rollback dependency
  based merely on alias movement. Automated repair/deletion is not implemented.
- Unlike Day 37's sanitized manifest-only upload, Day 38 deliberately uploads full
  model metadata, transform vocabulary and optional full evaluation/blend reports.
  These may contain local paths, profile-derived terms and cohort/employer details.
  Use approved data and restricted artifact access. Raw CSVs are excluded; tags
  contain operational identities/digests, not member data or secrets. The loopback
  development server is not an authenticated multi-user production deployment.

## Verification

```powershell
python -m pip check
python -W error -m unittest discover -s pymk-ml-training/tests
mvn -B -ntp verify
./scripts/day38-registry-smoke.ps1
./scripts/day23-release-smoke.ps1 -IncludeObservability -IncludeBlendTuning
```

The registry smoke accepts `-Python python` in CI and `-SkipBuild` for an already
built Day 36 MLflow image. Build the Java JAR first. It starts an isolated temporary
MLflow project, trains real models, publishes tracking and lineage, invokes the
production Java verifier, interrupts an upload, registers exact versions, changes
a discovery alias and intentionally corrupts a disposable remote artifact to
prove rejection. It removes only its own test containers/volumes and retains logs
under `artifacts/day38-smoke/`. CI runs this alongside Day 37 tracking checks.

Unit tests cover changed local/remote artifacts, failed parity, reused directories
and model versions, source/destination mismatches, concurrent publication, lost
acknowledgments, immutable release extension and alias independence. Java tests
cover legacy/schema-2 contracts and prove registry-backed activation cannot bypass
the future approval workflow or change the active epoch.

### Acceptance record — 2026-10-14

- Full Maven reactor: **267 tests, zero failures/errors/skips**, `BUILD SUCCESS`.
- Python: **94 tests passed** with warnings treated as errors; `pip check` found
  no broken requirements.
- Real MLflow registry smoke: exact L1/L2 bytes, actual packaged Java parity,
  evaluation/blend lineage, interrupted upload/retry, acknowledged repeat,
  alias redirected to different artifacts and remote corruption rejection passed.
  Evidence: `artifacts/day38-smoke/488e201367/`.
- Full packaged smoke with `-IncludeObservability -IncludeBlendTuning` exited zero:
  real training, HTTP L2/diversified scoring, configuration loading, cache isolation,
  activation/rollback, durable ingestion during Kafka outage, consumer replay,
  reconciliation and monitoring recovery after API restart all passed.
  Evidence: `artifacts/day23-smoke/4d5500a14d/`.
- An earlier packaged attempt lost its disposable Docker containers before API
  checks and was not counted as a pass. The fresh isolated run above completed;
  its disposable services and volumes were cleaned up successfully.

Top-level logs: `artifacts/day38-verify.log`, `artifacts/day38-python.log`,
`artifacts/day38-registry-smoke.log` and `artifacts/day38-serving-smoke.log`.
Generated evidence is ignored by Git. These checks verify the tested scenarios,
not an absence of every possible defect. Guarded promotion remains Day 39 work.

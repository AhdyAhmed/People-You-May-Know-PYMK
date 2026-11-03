# Day 37 — Reproducible experiment tracking

## Scope and installation

Tracking is opt-in for profile-transform fitting/application, L1 training, L2
training, frozen evaluation and blend tuning. Original algorithms, model formats
and default CLI behavior are unchanged. Java serving never calls MLflow.

From `pymk-ml-training`, install the pinned optional client:

```powershell
python -m pip install -e '.[heavy,tracking]'
```

The extra pins `mlflow-skinny==3.17.1`, matching the Day 36 server. Offline evidence
collection needs no MLflow SDK. Use the [Day 36 runbook](DAY36_MLFLOW_INFRASTRUCTURE.md)
to start the optional loopback-only service; this is not a shared authenticated
production deployment. Do not embed credentials in tracking URLs. Shared HTTPS,
authentication and object storage require deployment-specific validation.

## Three modes

Append these options to any existing command from Days 19–30:

| Options | Behavior |
|---|---|
| Neither option | Original untracked computation; no SDK import or tracking requests |
| `--tracking-dir artifacts/tracking` | Compute and write durable, explicitly unpublished local evidence |
| `--tracking-dir artifacts/tracking --tracking-uri http://127.0.0.1:5000` | Compute, then publish evidence and verify its downloaded checksum |

Use full option names in tracked mode. `--tracking-uri` requires `--tracking-dir`.
For example, append the flags to `python -m pymk_training.train_light_ranker`
with the existing repeated `--input`, `--output-dir` and `--model-version` arguments.
The same flags apply to `profile_embeddings`, `train_heavy_ranker`,
`evaluate_heavy_ranker` and `tune_blend`.

For a small runnable example from the repository root, with the training virtualenv
active and a fresh `artifacts/day37-demo` output directory:

```powershell
python -m pymk_training.profile_embeddings --input pymk-ml-training/tests/fixtures/profiles.csv --artifact artifacts/day37-demo/transform.json --output artifacts/day37-demo/vectors.csv --as-of 2026-01-03T00:00:00Z --fit --embedding-version day37-demo --tracking-dir artifacts/day37-demo/tracking
```

This collects offline evidence. Add `--tracking-uri http://127.0.0.1:5000` when
the Day 36 service is running, or publish the printed bundle later as below.

Each attempt prints its bundle directory. Publish an offline bundle or retry an
unconfirmed publication without recomputing:

```powershell
python -m pymk_training.tracking --bundle artifacts/tracking/<logical-id>/<attempt-id> --tracking-uri http://127.0.0.1:5000
```

The equivalent installed command is `pymk-publish-tracking`. Replace placeholders
with the printed path. Keep output files at their original paths for retries.
Default SDK HTTP timeout is 10 seconds with one retry, unless explicitly set by
`MLFLOW_HTTP_REQUEST_TIMEOUT` / `MLFLOW_HTTP_REQUEST_MAX_RETRIES`.
The adapter also defaults `MLFLOW_SUPPRESS_PRINTING_URL_TO_STDOUT=true`; its own
ASCII run-ID acknowledgment avoids MLflow's decorative URL output failing in
redirected Windows consoles.

## Evidence and privacy boundary

Experiments have stable names `pymk.profile.v1`, `pymk.light.v1`, `pymk.heavy.v1`,
`pymk.evaluate.v1` and `pymk.blend.v1`. Logical work identity hashes the operation,
parameters, input/sidecar digests, Python source digests and runtime/dependency
versions. Output paths and tracking destination do not affect logical identity.
Each computation and publication gets its own attempt ID.

The sanitized `manifest.json` contains source/build identity, parameters/seeds,
available dataset/release/transform lineage, temporal splits and partition cohort
hashes, aggregate metrics, gate outcome and role/checksum output references.
Source and knowledge cutoffs are retained where supplied by the existing dataset
and report contracts; missing provenance is not invented. Policies/contexts are
hash-bound. Undefined/non-finite metrics are recorded as unavailable, never zero.

Only this sanitized manifest is uploaded, then downloaded and checksum-verified.
Raw CSVs, member IDs, profile vocabulary, local file paths, arbitrary error text
and employer names are not uploaded. Numeric aggregate metrics and operational
IDs still require appropriate access controls. `local.json` contains private
absolute paths and `state.json` contains publication checkpoints: protect the
whole spool with filesystem access controls and backups. Training logs and full
local reports are outside the sanitized upload boundary.

Inputs are checksum-bound before and after computation and evidence extraction;
outputs are checked again before publication. Input files need not remain present
for later publication, but output bytes must still match. Do not concurrently
write to a command's input/output paths. Pre-computation validation failures may
have no bundle. Failed computations never claim earlier output files as new
evidence. The evaluator explicitly distinguishes a newly written failed quality
gate from a generic argument/computation failure.

## Outcomes, acknowledgment and recovery

- A successful computation returns its original code. If requested publication
  fails after successful computation, the CLI returns **3** and retains outputs.
  Original nonzero computation exits/exceptions remain failures.
- A `not_met` quality gate is recorded as `not_met`, never promoted to success.
  `--require-quality-gate` still exits **2**. Remote runs are `FAILED` for failed
  computations and failed gates, even when publication itself is complete.
- `pymk.publication=complete` means evidence upload/read-back, metrics and terminal
  status were acknowledged; it does not mean the model is good or approved.
  Local state becomes `published` only after those acknowledgments.
  Parameters, metrics and publication tags are explicitly synchronous even when
  `MLFLOW_ENABLE_ASYNC_LOGGING=true` is set in the worker environment.
- Publication pins the destination at the first attempt. An uncertain retry
  creates a new run with the same logical/computation identity and `retryOf` when
  the earlier run ID was received. A locally acknowledged retry is a no-op.
- Lost acknowledgments can produce duplicate remote runs, including an earlier
  apparently complete run. This is not exactly-once publication. Audit the
  logical/computation/publication tags together with the retained local state.
- An exclusive `.publish.lock` rejects concurrent publication. After a crash,
  confirm no publisher is running before removing that bundle's stale lock.
  A `computing` bundle is incomplete and cannot be published; rerun computation
  to a fresh output directory. Never manually mark an uncertain run published.
- Changed manifests, changed outputs and destination changes fail closed. Restore
  the original bytes from a trusted backup or rerun with a new output location;
  do not alter saved hashes to bypass validation.

No ONNX upload, model registration, alias changes, release preparation or automatic
promotion happens on Day 37. The separate [Day 38 adapter](DAY38_MODEL_REGISTRY.md)
adds exact registry candidates; guarded promotion remains Days 39–40. A tracked successful run is
not production approval, and the serving database pointer remains authoritative.

## Verification

```powershell
python -m pip check
python -W error -m unittest discover -s pymk-ml-training/tests
./scripts/day37-tracking-smoke.ps1
mvn -B -ntp verify
./scripts/day23-release-smoke.ps1 -IncludeObservability -IncludeBlendTuning
```

Use the training virtualenv's Python for the first two commands. The smoke script
accepts `-Python python` (CI) and `-SkipBuild` (only with an already built Day 36
image). It creates a unique disposable Compose project, publishes all five command
types, checks original numerical outputs, failed runs and `not_met` gates, stops
MLflow to prove honest failure, then resumes publication without retraining.
It removes only its own test containers/volumes and retains logs under
`artifacts/day37-smoke/`. CI installs the tracking extra and runs this smoke.
The smoke enables MLflow's async-logging environment setting to verify the
adapter still waits for its own writes.

Regression coverage also checks privacy filtering, undefined metrics, input/output
mutation, stale outputs, corrupted read-back, linked retries, destination pinning,
credential-URI rejection and concurrent publisher exclusion.

Verified on **2026-10-14**:

- `mvn -B -ntp verify`: **263 tests**, zero failures/errors/skips; `BUILD SUCCESS`.
- Python suite with warnings treated as errors: **74 tests passed**; `pip check` clean.
- Real MLflow smoke passed for all five commands, failed computation, failed
  quality gate, outage, restart and no-retraining/no-duplicate-acknowledged retry,
  with SDK async logging enabled. Evidence: `artifacts/day37-smoke/bc555162a8/`.
- Full packaged smoke with `-IncludeObservability -IncludeBlendTuning` passed:
  real training, Python/Java ONNX parity, HTTP L2/blend scoring, activation,
  caching, rollback, Kafka failure/replay/restart and monitoring recovery.
  Evidence: `artifacts/day23-smoke/fa179d9d0e/`.

Top-level local logs are `artifacts/day37-verify.log`,
`artifacts/day37-python-final.log`, `artifacts/day37-tracking-smoke.log` and
`artifacts/day37-serving-smoke.log`. Generated evidence is intentionally ignored
by Git. These checks do not certify shared remote workers or guarantee absence
of every possible defect. Subsequent registry acceptance is recorded in the
[Day 38 runbook](DAY38_MODEL_REGISTRY.md); promotion remains Days 39–40.

# Day 26 — Java two-head heavy scoring

The Java component executes the real Day 25 ONNX model, not a placeholder or
hand-written approximation. Public recommendations still use **L0 → L1**.
Day 27 supplies evaluation; Day 28 integrates L2 into HTTP orchestration and
cache versioning. Enabling the component does not switch the public endpoint.

## Enable the component

Train a model using the [Day 25 workflow](DAY25_HEAVY_TRAINING.md), then supply
these Spring properties to the API application:

```properties
pymk.heavy-ranker.enabled=true
pymk.heavy-ranker.model-path=artifacts/models/heavy/heavy-mlp-v1.onnx
pymk.heavy-ranker.metadata-path=artifacts/models/heavy/heavy-mlp-v1.metadata.json
pymk.heavy-ranker.max-candidates=500
```

Loading is disabled by default so existing L1 deployments need no heavy artifact.
Enabled startup fails if either file is missing or its contract is invalid.
Paths are relative to the process working directory; absolute paths are supported.
The application owns the model session and closes it at shutdown. Replace artifacts
through an application restart, not in-place hot reload. Heavy-model persistence
and atomic public pipeline promotion are not part of the Day 23 L1 release registry.

Inject the Spring-managed `L2HeavyRanker` and call
`score(memberId, l1Survivors)`. This is an internal Java interface, not a new HTTP
route. Its transactional proxy pins the active managed release. Callers must not
bypass that proxy; eventual public orchestration must hold one release pin across
L0, L1 and L2. Returned records carry both heads, original L1 candidate, heavy model
version, release ID and epoch. The input order is retained, including deterministic
L1 ties. Blending/reranking belongs to later days.

## Checked serving contract

- Exactly six ordered pair-v1 features, matching L1: mutual count, same company,
  same school, same geography, embedding cosine, recent profile-view count.
- Input `features`: dynamic float32 `[N,6]`. Outputs
  `invite_sent_probability` and `invite_accepted_probability`: dynamic float32
  `[N,1]`, resolved by name rather than runtime iteration order.
- Metadata schema/model type/version, feature order, tensor/label semantics,
  SHA-256 and the Day 25 opset-17/IR-8 declaration must match the adapter contract.
  Native runtime descriptors are checked independently; a warm-up checks outputs.
- A READY managed release is required. Pair schema, embedding version, transform
  checksum and fit cutoff must match the model. Validation label cutoff must not
  exceed the live feature snapshot. Legacy placeholder serving cannot use L2.
- L1 model version and feature snapshot must match the pinned release. All requested
  feature rows must exist and belong to this requester/snapshot; duplicates, foreign
  rows and mixed snapshots fail closed. There is no fallback to zero-valued features.
- Every survivor is scored in one model call after one batched feature read. Empty
  input returns empty without a read/inference; oversized batches are rejected,
  never truncated. The configurable cap is 1–10,000 (default 500).
- Missing/malformed outputs and nonfinite/out-of-range probabilities fail the whole
  batch. Bounds do not establish calibration or guarantee improved recommendations.
- Input tensors and result objects are closed per inference. Session shutdown is
  idempotent and locked against concurrent inference; the shared runtime environment
  remains available to other L1/L2 models.

## Reproduce verification

From the repository root, with Java 21, Maven, Docker and the Python heavy extra:

```powershell
mvn verify
Push-Location pymk-ml-training
.\.venv\Scripts\python.exe -W error -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -m pip check
Pop-Location
docker compose -f infra/docker-compose.yml config --quiet
./scripts/day23-release-smoke.ps1 -IncludeJavaHeavy
```

The isolated smoke exports fresh release-bound datasets, trains actual L1/L2
artifacts, creates a Python reference fixture, and runs the Java adapter against
that artifact (batch/singleton parity, malformed outputs, bad probabilities and
native lifecycle). It then checks real HTTP probabilities, caching and release
rollback. Containers and the API process created by the smoke are stopped afterward;
logs and artifacts remain under ignored `artifacts/day23-smoke/<run>/`.

The committed small fixture embeds the actual Day 25 trained ONNX bytes and
Python reference predictions, so ordinary tests do not depend on ignored artifacts
or retraining. A Python test independently reproduces those predictions. The API
integration test uses real PostgreSQL migrations, the release serving view, Spring
transaction pinning and native inference; it also verifies missing-feature failure.

## Verification record — 2026-10-04

The full 12-module `mvn verify` passed with **146 Java tests, zero failures,
errors or skips**. All **34 Python tests** passed with warnings treated as errors;
`pip check` and Docker Compose configuration validation passed. Local build evidence
is in `artifacts/day26-maven-verify.log` and `artifacts/day26-python-tests.log`.
The fresh `-IncludeJavaHeavy` smoke passed, including Python/Java parity, startup
with the heavy component enabled, actual HTTP probabilities/cache reuse and both
release rollbacks. Evidence: `artifacts/day26-smoke.log` and
`artifacts/day23-smoke/da8fc20672/` (including `heavy/java-parity.log`).
These checks establish the tested checkpoint, not a guarantee against every
possible defect or a production-scale latency/quality claim.

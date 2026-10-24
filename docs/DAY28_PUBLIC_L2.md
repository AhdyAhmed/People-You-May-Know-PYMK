# Day 28 — public L2 pipeline and rollback

The application can now serve **L0 → L1 → L2** at `GET /api/v1/pymk/{memberId}`.
L1 remains the default and explicit rollback mode. This is not automatic model
promotion: the Day 27 synthetic evaluation is insufficient evidence to choose L2
for production. The isolated tests opt in to exercise implementation correctness.

## Enable and roll back

Prepare and activate a compatible managed release using the Day 23 workflow and
train/verify the matching heavy artifact using Days 25–27. Then start the API with:

```powershell
java -jar pymk-api/target/pymk-api.jar `
  --pymk.serving.ranker=L2 `
  --pymk.light-ranker.max-candidates=200 `
  --pymk.heavy-ranker.enabled=true `
  --pymk.heavy-ranker.model-path=artifacts/models/heavy/heavy-mlp-v1.onnx `
  --pymk.heavy-ranker.metadata-path=artifacts/models/heavy/heavy-mlp-v1.metadata.json
```

Use your actual model paths and existing datasource/Redis configuration. Equivalent
environment variables are `PYMK_SERVING_RANKER`, `PYMK_L2_ENABLED`,
`PYMK_L2_MODEL_PATH`, and `PYMK_L2_METADATA_PATH`. The L1 survivor budget defaults
to 500; the example explicitly uses 200 to match the checked-in Day 27 evaluation
policy. Evaluate the exact source/survivor budgets you intend to deploy; the tiny
smoke is not promotion evidence for either budget. The L2 maximum batch must be
at least the configured L1 budget. Startup rejects unknown
modes, missing enabled artifacts, missing L2 components or insufficient L2 capacity.

For a process-level rollback, restart with:

```powershell
java -jar pymk-api/target/pymk-api.jar `
  --pymk.serving.ranker=L1 --pymk.heavy-ranker.enabled=false
```

Preserve datasource/Redis and L1 artifact settings. A managed release can load L1
from the database. A legacy deployment needs its captured or local L1 artifact.
Disabling heavy loading means rollback does not require a working L2 file/session.
There is no live mutable mode switch or silent per-request fallback. Deployment
orchestration and zero-downtime rolling promotion remain operational work.

Legacy releases cannot serve L2. If the active pointer rolls back to legacy while
the process is in L2 mode, requests fail `503`; switch to L1 deliberately. Health
`UP` alone does not prove release compatibility: check an actual recommendation
request after activation. The release/model/transform must pass Day 26 checks.

## Public score and buffer contract

- `X-PYMK-Scorer: L1` means the existing raw class-weighted L1 acceptance score.
- `X-PYMK-Scorer: L2` means the heavy model's named
  `invite_accepted_probability`. L2 sorts by that score descending and candidate
  ID ascending for exact ties. The sent head is still computed and validated,
  but it does not participate in the public score yet.
- Neither mode claims calibrated acceptance rates or causal engagement uplift.
  The response JSON shape remains unchanged, including mutual count/reasons.
- All L1 survivors enter one L2 scoring call **before** the response cap. The
  reusable buffer stores the best 100 results, then slices requests of 1–100.
  This is not yet Day 29's blended/diversified scored-pool buffer.
- The whole request holds the Day 23 release pin through L0, L1, L2 and cache
  publication. L2 validates managed release compatibility even on final-cache hits.
- Incompatible releases, missing features and model failures return the existing
  retryable ProblemDetail `503`; failed/partial final results are never cached.
  Complete L0 candidates may remain cached if later scoring fails. Redis failures
  continue as cache misses; PostgreSQL/model failures do not masquerade as misses.
- Member existence and L0 eligibility remain enforced. The status endpoint `/`
  reports Day 28 and the configured scorer. OpenAPI documents the mode header and
  both score meanings; there is no re-ranking/blending until Day 29.

## Cache coherence

Both layers retain their configured prefix/schema/pipeline/model salt and release
ID/activation epoch. Day 28 additionally appends an automatically derived scorer
identity: scoring semantics, L1 survivor budget, and (for L2) SHA-256 of model bytes
plus parsed metadata. Changing heavy bytes or metadata isolates entries even if a
version name was accidentally reused. Versioned immutable publication is still
required. The configured `pymk.cache.pipeline-version` is an additional deployment
salt, not a substitute for the derived identity.

Each request also reads and pins PostgreSQL's existing `pymk_source_revision`.
Member, connection and event mutations increment it transactionally. It is part
of both cache keys in both modes. Therefore switching back to L1 cannot expose an
old L1 entry after graph changes made while L2 was active. An in-flight old request
can populate only its old revision, not the next request's generation. After-commit
neighborhood eviction remains; unreachable generations expire through normal TTLs.

This is intentionally a **global** revision: unrelated source changes invalidate
cache reuse too. It adds a database read even on cache hits and can reduce hit rate
under event-heavy workloads. Per-member revisions/streaming invalidation need later
operational design. This does not promise serializable graph responses concurrent
with writes: an in-flight request may reflect data before a concurrent commit.
Direct edits to immutable release-feature/model rows are unsupported; publish a
new managed release instead. Feature coverage gaps after graph changes still fail
closed until a compatible refresh exists.

## Timing and verification

`pymk.pipeline.stage` Micrometer timers have bounded tags `stage` (`l0`, `l1`,
`l2`, `request`), `mode` and `outcome`; they record p50/p95/p99. L0 measures source
generation, L1/L2 measure batch ranking/scoring, and request includes admission,
release/cache work and transaction completion. Final-cache hits skip all three
scoring stages but still pay compatibility/cache costs. No member/model IDs are
metric tags. Actuator remains limited to health/info by default; exposing metrics
requires an explicit protected deployment configuration.

The real HTTP test writes `pymk-api/target/day28-latency.json`: OS/processor/JVM
details, data size, concurrency, request mix and per-stage/end-to-end quantiles.
It uses four members, two directed connection rows, two L1 survivors, concurrency
one, five excluded warm-up requests and 30 requests each with cold and warm Redis.
Cold means a cache flush before each timed HTTP call, not process/model cold-start;
flush time is excluded. End-to-end includes local HTTP client parsing/assertions.
Stage quantiles are Micrometer estimates; HTTP quantiles use nearest-rank samples.
Warm inference-stage call counts must be zero. This tiny diagnostic fixture is
not a scale/load test or proof of the 150 ms p99 target under production traffic.

From the repository root:

```powershell
mvn verify
Push-Location pymk-ml-training
.\.venv\Scripts\python.exe -W error -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -m pip check
Pop-Location
docker compose -f infra/docker-compose.yml config --quiet
./scripts/day23-release-smoke.ps1 -IncludeL2Serving
```

The smoke creates its own PostgreSQL/Redis containers and fresh artifacts. It runs
evaluation and Java parity, boots the packaged API in L2 mode, verifies incompatible
legacy rollback yields `503`, restarts in L1 without loading heavy artifacts, then
re-enables L2 on the compatible release with the same Redis instance. All smoke
processes/containers are stopped; evidence remains in ignored `artifacts/`.

## Measured latency — 2026-10-04

The full regression run produced the following measurements, in milliseconds,
on Windows 11, Java 21.0.12, 8 visible processors (Intel64 Family 6 Model 94
Stepping 3), with a maximum JVM heap of 12,859,736,064 bytes. The workload and
sample-size restrictions above apply; these are diagnostic measurements, not a
production benchmark. Warm scoring stages were not invoked (zero calls), so no
invented warm-stage percentiles are shown.

| Phase / operation | p50 | p95 | p99 |
| --- | ---: | ---: | ---: |
| Cold HTTP, including client parsing | 66.88 | 113.93 | 171.34 |
| Cold L0 generation | 14.68 | 28.84 | 79.17 |
| Cold L1 ranking | 3.41 | 5.11 | 5.37 |
| Cold L2 scoring | 7.08 | 14.42 | 21.76 |
| Cold server request | 50.33 | 94.37 | 148.90 |
| Warm HTTP, including client parsing | 30.03 | 34.35 | 35.96 |
| Warm server request | 17.30 | 20.45 | 20.45 |

The cold end-to-end p99 exceeds 150 ms in this small run; the target is **not
established**. Day 28 requires measurement, not a claim that the later load/SLO
milestone is complete. Stage percentiles are not additive. Broader request mixes,
representative data, larger samples and concurrency/load testing remain necessary.

## Verification record — 2026-10-04

- Full 12-module `mvn verify`: **154 Java tests**, zero failures/errors/skips.
- Python with warnings treated as errors: **46 tests passed**; dependency checks
  and Docker Compose configuration validation passed.
- Actual HTTP tests verified heavy-artifact acceptance-head parity, eligibility,
  reuse across limits, warm-hit compatibility checks, connection-mutation fencing,
  missing-feature/model-failure `503` responses and the latency record above.
- Fresh packaged smoke passed L2 serving, incompatible legacy release `503`,
  L1-only process rollback (heavy loading disabled), L2 re-enable with shared Redis,
  both release rollbacks, evaluation and Java/Python parity. Its four-member quality
  gate remains deliberately unmet; serving tests do not promote the model.
- Evidence: `artifacts/day28-maven-verify.log`, `artifacts/day28-python-tests.log`,
  `artifacts/day28-smoke.log`, `artifacts/day23-smoke/f1d3f4c8b0/` and
  `pymk-api/target/day28-latency.json`. Smoke API logs are named by restart/mode,
  such as `api-1-L2.log`, `api-2-L1.log` and `api-3-L2.log`.

These checks establish the tested functionality, not a guarantee against every
possible defect. Model promotion and the end-to-end latency target remain unproven.

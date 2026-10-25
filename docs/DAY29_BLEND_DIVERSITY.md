# Day 29 — weighted blend and employer exposure selection

The public endpoint now supports the complete **L0 → L1 → L2 → re-ranker**
pipeline through explicit `RERANK` mode. L1 remains the default; L2 acceptance-only
serving remains available. No model-quality promotion is implied. Day 30's offline
weight search is still pending: the default equal weights are a configuration
baseline, not an optimized or validated engagement policy.

## Enable and roll back

First prepare a compatible managed release and matching heavy model using the
[Day 28 prerequisites](DAY28_PUBLIC_L2.md). Retain your datasource/Redis settings:

```powershell
java -jar pymk-api/target/pymk-api.jar `
  --pymk.serving.ranker=RERANK `
  --pymk.light-ranker.max-candidates=200 `
  --pymk.heavy-ranker.enabled=true `
  --pymk.heavy-ranker.model-path=artifacts/models/heavy/heavy-mlp-v1.onnx `
  --pymk.heavy-ranker.metadata-path=artifacts/models/heavy/heavy-mlp-v1.metadata.json `
  --pymk.reranker.sent-weight=0.5 `
  --pymk.reranker.accepted-weight=0.5 `
  --pymk.reranker.employer-cap-fraction=0.4
```

Equivalent environment variables: `PYMK_SERVING_RANKER`, `PYMK_L2_ENABLED`,
`PYMK_L2_MODEL_PATH`, `PYMK_L2_METADATA_PATH`, `PYMK_BLEND_SENT_WEIGHT`,
`PYMK_BLEND_ACCEPTED_WEIGHT`, `PYMK_EMPLOYER_CAP_FRACTION`.
The example retains the Day 27 evaluation's 200-survivor budget; the application's
default is 500. Evaluate the exact budgets, blend and selection policy before
deployment. L2 capacity must cover every L1 survivor.

Restart with `--pymk.serving.ranker=L2` for acceptance-only rollback, keeping the
heavy artifacts enabled. For L1 rollback use `--pymk.serving.ranker=L1
--pymk.heavy-ranker.enabled=false` and retain compatible L1/release settings.
Configuration is immutable within a process. There is no silent fallback or live
weight mutation. Unknown modes, invalid weights/caps and missing heavy components
fail startup. Legacy releases cannot serve L2 or RERANK; a real recommendation
request, not just health `UP`, is required after activation.

## Score, order and exposure contract

- Weights must be finite, nonnegative, and not both zero. Normalize them to sum
  to one, scaling first to avoid overflow for large finite inputs.
- `score = normalizedSentWeight * P(sent) + normalizedAcceptedWeight * P(accepted)`.
  Both inputs and the output are in `[0,1]`. This is a ranking utility, not a
  calibrated acceptance probability, causal lift estimate, or fairness guarantee.
- Order by score descending, then candidate ID ascending. Greedily skip a
  candidate if its known employer has reached
  `max(1, floor(employerCapFraction * requestedLimit))`. The fraction must be in
  `(0,1]`. Never relax the count cap to fill a response.
- Employer groups use current member company values, Unicode `strip()` followed
  by `Locale.ROOT` lowercase. Internal spaces and legal-name aliases are not
  merged. Null/blank groups are exempt and counted separately, not grouped into
  one artificial employer. This does not guarantee diversity among unknowns.
- Return fewer results if the pool cannot satisfy the requested size. The cap is
  based on **requested size**, not returned size. With one employer and limit 20,
  cap 0.4 permits eight results whose realized employer fraction is **100%**.
- The returned list remains a descending-score subsequence after filtering;
  scores are never overwritten to encode display position. Requests at different
  limits need not be prefixes of one another because their caps differ.
- `X-PYMK-Scorer: RERANK` identifies these semantics. L1/L2 retain their existing
  score meanings and response shape. RERANK adds an `exposure` envelope:

```json
{
  "requestedLimit": 20,
  "returnedSize": 8,
  "knownGroupCap": 8,
  "knownGroupCounts": {"acme": 8},
  "unknownGroupCount": 0,
  "maxKnownGroupFraction": 1.0
}
```

For an empty response, counts and maximum fraction are zero. Group fractions can
be calculated as `groupCount / returnedSize`; the maximum is also reported.
This is employer **exposure diversity**, not protected-attribute fairness.

## Cache and failure behavior

RERANK stores every L1 survivor's blended score, reasons and normalized employer
in `scored-pool-v1`, **before selection and without truncating to 100**. Each cold
pool uses one batched member lookup and one L2 inference batch. Every request,
including a warm hit, applies selection using that request's limit.

The derived `day29-blend-employer-v1` identity includes normalized weights, cap,
survivor budget and heavy artifact identity. It supplements existing schema/model
salts, pinned source revision and release ID/epoch. Weight/cap/model/mode changes
cannot reuse incompatible entries. Member profile and connection changes advance
source revision, so old employer groups or eligibility cannot poison later cache
generations. Old generations expire by TTL; in-flight consistency retains the
Day 28 guarantees and is not a serializable graph snapshot.

L1/L2 result caches remain distinct and unchanged. RERANK never caches a selected
page for reuse at another limit. Redis failure degrades to recomputation; corrupt,
wrong-owner, self/duplicate and over-budget pools are discarded. Release
compatibility is checked even on warm hits. Missing members/features, incompatible
releases and inference failures return `503`, not partial successful pools.

The whole request remains release-pinned. The `rerank` stage timer runs on cold
and warm requests alongside existing `l0`, `l1`, `l2`, and `request` timers.
This implementation is not evidence of production latency or model quality.

## Reproducible verification

```powershell
$env:MAVEN_OPTS='-Djavax.net.ssl.trustStoreType=Windows-ROOT'
mvn verify
& pymk-ml-training/.venv/Scripts/python.exe -W error -m unittest discover -s pymk-ml-training/tests -v
& pymk-ml-training/.venv/Scripts/python.exe -m pip check
docker compose -f infra/docker-compose.yml config --quiet
pwsh -NoProfile -File scripts/day23-release-smoke.ps1 -IncludeReranker
```

The new unit and actual HTTP tests cover normalized/invalid weights, deterministic
ties, underfilling, entirely blank groups, limits 1/2/20/100 sharing one cache,
242-survivor backfilling, Python/Java heavy-head blend parity, profile/connection
invalidation, warm-hit compatibility rejection and failed-inference cache safety.
The smoke trains fresh artifacts, activates a release, serves real packaged HTTP,
checks mode-aware exposure/score invariants, and exercises L1/L2/RERANK rollback
and re-enable with shared Redis. Its isolated containers/processes are cleaned up.

Verification on 2026-10-08: `mvn verify` passed all 12 reactor modules and
**169 Java tests, zero failures/errors/skips**. All **46 Python tests** passed
with warnings treated as errors; `pip check` and Compose configuration validation
passed. Logs are in ignored `artifacts/day29-maven-verify.log` and
`artifacts/day29-python-tests.log`. No zero-bug or production-SLO guarantee can be
inferred from a finite test suite.

The packaged fresh-artifact smoke also passed with `-IncludeReranker`:
`artifacts/day29-smoke.log`, run directory `artifacts/day23-smoke/14fada35a1/`.
It verified Java/Python parity, actual HTTP at all four limits, release activation,
incompatible-release `503`, L1/L2 rollback and RERANK re-enable with shared Redis.
The four-member synthetic cohort correctly reported quality gate `not_met` for
insufficient evaluation evidence; this is not a production promotion or SLO claim.

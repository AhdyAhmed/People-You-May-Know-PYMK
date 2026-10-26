# Day 30 — reproducible Bayesian blend tuning and M5 closure

Day 30 adds an offline tuner that produces an immutable configuration for the
existing Day 29 Java re-ranker. It does not retrain a model, change a live release,
or enable RERANK automatically. L1 remains the default. Kafka remains the separate
Days 31–35 milestone.

## Fixed objective and search

`pymk-ml-training/blend-policy.json` declares the objective, seed, trial budget,
requested result size, L1 survivor budget and employer cap **before** tuning.

- Optimize requester/snapshot-macro **accepted hits / requested k**, after
  employer-constrained selection. Zero-positive queries remain in the macro.
  Using requested slots, not returned rows, penalizes underfill rather than
  rewarding a policy for returning only one positive. Sent hits per requested
  slot and actual returned size are reported as secondary diagnostics.
- Search one scalar `sentWeight` in a fixed 1,001-point `[0,1]` grid;
  `acceptedWeight = 1 - sentWeight`. The employer cap is fixed, not tuned.
  Invalid probabilities, weights, limits or caps are rejected. Both validation
  acceptance classes must be present among L1 survivors; otherwise tuning fails.
- Start with acceptance-only, equal weights and sent-only. Subsequent trials use
  a Gaussian-process posterior with fixed RBF length scale 0.2, noise variance
  `1e-6`, unit prior variance, observed mean, and expected improvement offset
  0.001. The seed fixes grid tie order. Budget includes the three initial trials
  and is bounded to 3–64; default 20. This is Bayesian optimization over a finite
  domain, not a claim of a global optimum.
- Select the highest validation objective. Exact ties prefer the weight closest
  to 0.5, then the smaller sent weight. Flat evidence retains equal weights.
- Freeze the selected weights **before** computing test metrics. Test predictions
  are computed in one heavy batch and evaluated for the fixed selected policy
  and the three predeclared baselines; they never select or retune the blend.
  Earlier Day 27 reports may already describe this frozen test cohort: “held out”
  here means excluded from this search, not that nobody has ever inspected it.

Day 29 normalization, greedy score/ID ties, decimal-safe count caps and blank
employer handling are reproduced offline. Java and Python consume the same
policy-parity fixture. Underfilled responses can still have 100% exposure to one
employer; this is not a protected-attribute fairness or calibration guarantee.

## Cohorts and honest scope

Inputs are the immutable Day 24 v2 datasets, their provenance sidecars, and the
frozen compatible L1/heavy artifacts from Days 25–27. Shared validation checks
model checksums, tensor contracts, embedding transform, chronological split and
training-input identity. No training occurs during tuning.

The tuner evaluates an **exported-pool proxy**: per requester/snapshot, keep the
configured number of highest raw-L1 scores, breaking ties by candidate ID, then
apply the blend and diversity cap. It does **not** replay historical L0 eligibility
or the online L1 fusion-score tie-breaker. Thus this is not full-population recall,
causal engagement lift, or exact online policy replay. Validation also selected
the heavy checkpoint previously, which limits independence. Obtain stronger
historical replay and independent evaluation evidence before production promotion.

Employer values must come from immutable as-of context, never silently from
today's member table. Supply a JSON file with this shape, covering every dataset
pair/snapshot exactly once:

```json
{
  "schemaVersion": 1,
  "scope": "exported-pool-proxy",
  "datasetSha256": ["sorted CSV SHA-256 values from the inputs"],
  "rows": [
    {"featureAsOf": "2026-01-10T00:00:00.000000Z", "memberId": 1,
     "candidateId": 2, "employer": "Acme"}
  ]
}
```

Use the exact canonical `feature_as_of` strings produced by the dataset loader.
Explicit null/blank employer is valid; an omitted field or missing/duplicate pair
is not. Hash binding detects mismatched inputs, not dishonesty in a historical
exporter's assertions. `blend_context_fixture` is only a synthetic smoke helper:
it validates the supplied profile snapshot predates the cohorts and explicitly
assumes those synthetic profiles remain constant across snapshots. Do not use it
as a production history reconstruction tool.

## Tune and inspect

Use the Day 25 Python environment with the `heavy` extra installed. From the
repository root, after producing the three v2 exports and compatible
model files (substitute actual paths):

```powershell
& pymk-ml-training/.venv/Scripts/python.exe -m pymk_training.tune_blend `
  --input artifacts/train-v2.csv --input artifacts/validation-v2.csv --input artifacts/test-v2.csv `
  --model artifacts/models/heavy/heavy-mlp-v1.onnx `
  --metadata artifacts/models/heavy/heavy-mlp-v1.metadata.json `
  --l1-model artifacts/models/light-logreg-v1.onnx `
  --l1-metadata artifacts/models/light-logreg-v1.metadata.json `
  --policy pymk-ml-training/blend-policy.json `
  --context artifacts/historical-employers.json `
  --output-dir artifacts/blends/blend-v1
```

The output directory must not exist. It contains:

- `application-blend.properties`: both weights, the fixed employer cap, and
  matching L1/L2 survivor capacities. It deliberately does not set serving mode.
- `report.json`: completion marker with seed/budget/policy hash, every trial,
  selected/effective normalized weights, validation/test baseline comparisons,
  per-query IDs/exposure/hits, cohort hashes, dataset provenance, artifact/context/
  configuration/code hashes and runtime versions. `automaticPromotion` is false.

Input bindings are rechecked before publication. Files are published exclusively;
existing releases are never overwritten. An interrupted partial directory is
inactive—use a new version, and never deploy a directory lacking a complete
report with matching configuration/model checksums. Keep the report and its exact
inputs together; artifacts remain gitignored and require your artifact storage
and retention procedure. Rerunning deterministic inputs reproduces the report
within the recorded runtime; versions may change floating-point behavior.

## Deliberate deployment and rollback

Inspect validation and test comparisons and limitations first. A high synthetic
objective alone is not a production quality gate. Keep the previous process
configuration and compatible model/release available. For an approved local demo:

```powershell
java -jar pymk-api/target/pymk-api.jar `
  --spring.config.additional-location=file:artifacts/blends/blend-v1/application-blend.properties `
  --pymk.serving.ranker=RERANK `
  --pymk.heavy-ranker.enabled=true `
  --pymk.heavy-ranker.model-path=artifacts/models/heavy/heavy-mlp-v1.onnx `
  --pymk.heavy-ranker.metadata-path=artifacts/models/heavy/heavy-mlp-v1.metadata.json
```

Retain the datasource/Redis settings and activate the matching managed release
using [Day 23](DAY23_RELEASES.md). Before startup, compare the report's heavy,
metadata, L1 and configuration hashes against the exact deployment artifacts.
Command-line/environment overrides must not silently replace approved weights or
budgets. Spring loads the properties; the existing derived scorer identity isolates
weight/cap/budget/artifact cache namespaces automatically. No DB migration is
needed for this offline configuration artifact.

Verify an actual recommendation request and `X-PYMK-Scorer: RERANK`, not health
alone. To roll back weights, restart using the previous immutable configuration
and matching artifacts. To roll back mode, restore the previous arguments/config
and select L2 acceptance-only, or L1 with heavy loading disabled. These are process
restarts, not a zero-downtime deployment system. See [Day 29](DAY29_BLEND_DIVERSITY.md)
for selection semantics and [Day 28](DAY28_PUBLIC_L2.md) for release compatibility.

## Reproducible M5 demo and verification

Docker Desktop must be running. From the repository root:

```powershell
$env:MAVEN_OPTS='-Djavax.net.ssl.trustStoreType=Windows-ROOT'
mvn verify
& pymk-ml-training/.venv/Scripts/python.exe -W error -m unittest discover -s pymk-ml-training/tests -v
& pymk-ml-training/.venv/Scripts/python.exe -m pip check
docker compose -f infra/docker-compose.yml config --quiet
pwsh -NoProfile -File scripts/day23-release-smoke.ps1 -IncludeBlendTuning
```

The smoke creates isolated PostgreSQL/Redis, seeds the synthetic network/events,
exports chronological features/datasets, trains fresh L1/L2 artifacts, tunes on
validation, publishes configuration, activates a managed release, and loads that
configuration into the **packaged Java HTTP application**. It tests requested
limits 1/2/20/100, scored-pool reuse, incompatible-release `503`, L1/L2 rollback,
and RERANK re-enable. It checks the configuration digest and the effective tuned
survivor-budget cache namespace. Only its own processes/containers are cleaned up.
The tiny cohort must still fail the Day 27 production-quality gate; that expected
outcome is distinct from smoke correctness. No finite suite guarantees zero bugs.

Verification on 2026-10-08: all 12 Maven reactor modules passed `mvn verify`,
with **170 Java tests, zero failures/errors/skips**. All **57 Python tests** passed
under `-W error`; dependency and Compose validation passed. Evidence is in ignored
`artifacts/day30-maven-verify.log` and `artifacts/day30-python-tests.log`.

The fresh `-IncludeBlendTuning` packaged smoke passed as well:
`artifacts/day30-smoke.log`, immutable run directory
`artifacts/day23-smoke/4694bbfdbb/`. Its 20-trial search retained equal weights
on the tiny tied validation cohort (no improvement over that baseline is claimed).
The exported 200-survivor configuration loaded into the actual RERANK HTTP
process; limits 1/2/20/100, cache namespaces, L1/L2 rollback and RERANK re-enable
passed. The Day 27 quality gate correctly remained `not_met` for insufficient
evidence. Temporary smoke processes and containers were removed after the run.

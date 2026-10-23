# Day 27 — frozen offline evaluation

Day 27 adds an executable evaluation CLI for the actual heavy ONNX artifact,
the exact frozen L1 comparator and the mutual-connections baseline. It does not
train, calibrate, mutate models, activate releases or change the public L1 API.

## Run

Use the three chronological v2 exports and both model artifacts from the
[Day 25 workflow](DAY25_HEAVY_TRAINING.md). With the Python `heavy` extra installed:

```powershell
./pymk-ml-training/.venv/Scripts/python.exe -m pymk_training.evaluate_heavy_ranker `
  --input artifacts/cohort-train.csv --input artifacts/cohort-validation.csv --input artifacts/cohort-test.csv `
  --model artifacts/models/heavy/heavy-mlp-v1.onnx `
  --metadata artifacts/models/heavy/heavy-mlp-v1.metadata.json `
  --l1-model artifacts/models/light-logreg-v1.onnx `
  --l1-metadata artifacts/models/light-logreg-v1.metadata.json `
  --policy pymk-ml-training/evaluation-policy.json `
  --output artifacts/evaluation/run-001.json
```

Replace the example paths with your artifacts. The L1 training CSV paths recorded
in its metadata must still resolve, because the evaluator rechecks their bytes,
ordered pairs, features, acceptance labels and chronological split against v2.
Heavy input hashes/provenance, model checksum, embedding contract, tensor semantics
and native named-output descriptors are also checked. Equivalent UTC encodings
compare as instants. Invalid artifacts fail before publishing a report.

Reports are exclusively and atomically published; an existing path is never
overwritten. Use a new output filename for another run. Reports include model,
metadata, dataset, policy, evaluator-code and optional replay-context hashes plus runtime versions. Their JSON contains
no NaN/Infinity. The same inputs and policy reproduce the same report.

The default command exits successfully when evaluation completes, **even when
the quality gate is not met**. Add `--require-quality-gate` for an exit code of 2
after saving a report whose gate is not met. Invalid inputs fail separately and
do not publish an evaluation report. Installing the package also exposes
`pymk-evaluate-heavy-ranker` with the same arguments.

## Metric definitions

Validation and test are reported separately. Validation is diagnostic because
Day 25 used it for checkpoint selection. There is no additional fitting and no
selection of a winning variant from the test report.

| Measure | Definition and edge cases |
| --- | --- |
| AUC / average precision | Per-row binary metrics on the frozen cohort; null for empty or single-class cohorts, with a reason. |
| ECE | Ten equal-width probability bins by default; absolute observed-rate minus mean prediction, weighted by bin population. Probability 1 belongs to the final bin. Empty bins carry null statistics. |
| Brier | Mean squared probability error. Raw L1 is evaluated independently; class weighting does not make its output a calibrated acceptance rate. |
| Precision@k | Macro mean over nonempty `(requester, feature snapshot)` queries; denominator is `min(k, available candidates)`, not silently padded slots. |
| Recall@k within pool | Macro mean of retrieved positives / positives in that query's scored pool. Queries with no positives are excluded and counted separately. |
| Coverage | Number of observed queries, positive queries and fraction able to fill k slots; this is not coverage of all registered members. |
| Uncertainty | Seeded paired requester-cluster percentile 95% bootstrap. All snapshots of a sampled requester travel together. Repeated draws remain distinct clusters. Reports precision, within-pool recall, AUC, probability ECE and precision difference against mutual count. |

Bootstrap AUC replicates with only one class are omitted and the valid-replicate
count is exposed. Fewer than two independent requesters yields unavailable
intervals, never a zero-width confidence claim. Ties are resolved by ascending
candidate ID. AUC/ECE are row-weighted; ranking is query-macro. Bootstrap keeps
the paired model/baseline rows together. Subgroup point estimates are diagnostic;
they are not simultaneous confidence claims across many comparisons.

Both heavy targets are compared against mutual-count ranking on identical rows.
L1 is an acceptance comparator only. Mutual counts are not probabilities and
therefore receive neither ECE nor Brier scores. These are retrospective labels,
not an online A/B experiment or an estimate of causal acceptance uplift.

## Historical cohorts and stage losses

V2 exports do **not** contain requester profile age/blankness/degree or L0 source
membership. Do not infer these from mutual count, zero cosine or today's profile.
Without a replay context, the report explicitly marks these analyses unavailable
and the quality gate cannot pass. Core ranking/calibration evaluation still runs.

To include those analyses, supply `--context replay-context.json`, captured by a
historical replay at the same feature snapshots. This is an input contract, not a
new automatic exporter or a claim that the public API already records traces.
The replay producer is responsible for the historical facts; hashes establish
which evidence was used, not the truth of arbitrary supplied context.

```json
{
  "schemaVersion": 1,
  "datasetSha256": ["sorted CSV SHA-256 values, one per input"],
  "candidateBudgets": {"GRAPH_WALK": 2000, "HEURISTIC": 1000, "EMBEDDING": 1000},
  "rows": [{
    "featureAsOf": "2026-01-17T00:00:00Z",
    "memberId": 1,
    "candidateId": 2,
    "blankProfile": false,
    "newMember": true,
    "degree": 3,
    "sources": ["HEURISTIC", "EMBEDDING"]
  }]
}
```

The example is structural: supply every exported pair exactly once and use the
canonical snapshot strings from the dataset. Define blank profile as all of
headline/company/school/geography empty; new member as account age at most 30 days
at the snapshot; degree as the snapshot's directed adjacency count. Historical
context must use those definitions consistently. Sources are the actual source
outputs **after their budgets**, before merge. An empty list means absent from L0
within the exported pool. Overlapping sources are allowed; duplicate/unknown
sources, excess budgets, missing/foreign/duplicate pairs, differing dataset hashes
or inconsistent requester attributes are rejected.

Reports include blank/nonblank, new/established, degree 0 / 1–9 / 10+, graph-only, and each
candidate source. Empty groups remain explicit. The evaluator replays deterministic
L1 top-N selection using the frozen L1 scores and the policy's survivor budget,
then evaluates every heavy score on those same survivors. It counts positives
remaining/lost at L0 and L1 for both targets. L2 scoring itself drops no survivors;
top-k display selection is described by the ranking metrics, not mislabeled as
loss during inference. L0 and L1 pool metrics appear separately.

**Full eligible-population recall remains unavailable.** The exported materialized
pair pool may already exclude eligible pairs, so losses before that pool cannot be
measured by this trace format. Likewise, requests with no exported candidates are
not silently counted as covered. Broader candidate recall needs an independently
frozen eligible-population denominator and an expanded replay dataset.

## Fixed quality policy

`pymk-ml-training/evaluation-policy.json` fixes k=10, L1 survivors=200, source
budgets, 10 ECE bins, seed 42 and 500 bootstrap draws. It requires at least 30 test
requesters, 20 positive examples per target, AUC ≥0.5, ECE ≤0.1, and no more than
0.02 absolute precision regression against mutual count, including the lower
paired-bootstrap difference bound. Missing context or uncertainty blocks the gate.
These are initial offline evidence thresholds, not a production launch approval.

The policy is validated before evaluation and embedded with its hash. Freeze it
before examining a new test comparison; changes require a new policy/review and
new held-out evidence. Day 25 already reported metrics on the existing fixture,
so Day 27 cannot retroactively claim a preregistered blind experiment on it.
The tiny smoke dataset is deliberately insufficient for the gate. A `not_met`
result is a valid evaluation result, not a failed inference or broken application.
No automatic promotion is implemented, even if all checks are met.

Calibration fitting is intentionally absent: a genuinely untouched calibration
partition is needed separately from the validation data used for checkpoint
selection. Evaluation does not silently repurpose the test data for calibration.

## Reproduce checks

```powershell
mvn verify
Push-Location pymk-ml-training
.\.venv\Scripts\python.exe -W error -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -m pip check
Pop-Location
docker compose -f infra/docker-compose.yml config --quiet
./scripts/day23-release-smoke.ps1 -IncludeEvaluation
```

The extended smoke runs fresh exports/training/evaluation, Java ONNX parity,
heavy-enabled API startup, HTTP/cache checks and both release rollbacks. It asserts
that the small synthetic cohort does not pass the promotion gate. Automated tests
also exercise supplied historical context, subgroups, stage-loss accounting,
malformed inputs, deterministic bootstrap, metric edge cases and report immutability.

## Verification record — 2026-10-04

- Full 12-module `mvn verify`: **146 Java tests**, zero failures/errors/skips.
- Python with warnings treated as errors: **46 tests passed**, including equivalent
  UTC cutoff encodings and consistent float32 ECE/bootstrap bin boundaries.
- Dependency consistency, installed CLI help and Docker Compose validation passed.
- Fresh export/training/evaluation, Java parity, heavy-enabled API startup,
  HTTP/cache reuse and both release rollbacks passed. Evidence is under
  `artifacts/day23-smoke/47d416dd4a/`; the final evaluator was rerun on those same
  artifacts after the bin-boundary fix, producing `heavy/evaluation-final.json`.
  Its evaluator-code hash matches the final source.
- Top-level logs: `artifacts/day27-maven-verify.log`, `day27-python-tests.log`,
  `day27-smoke.log` and `day27-final-evaluation.log` (all under `artifacts/`).

The smoke cohort correctly returned `qualityGate.status=not_met`: historical
replay context is absent and requester/positive support is too small. No promotion
or serving change occurred. These checks establish the tested implementation,
not a guarantee against every possible defect or proof of production model quality.

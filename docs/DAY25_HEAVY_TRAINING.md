# Day 25 — offline two-head heavy ranker

Day 25 trains and exports a real PyTorch model, but does not change serving.
The public application still runs L0 → L1. Java L2 loading is Day 26; public
L2 cutover is Day 28. No offline result automatically promotes a heavy model.

## Setup and inputs

Use the Python environment from `pymk-ml-training/README.md`. Install CPU PyTorch
before the heavy extra to avoid downloading an unnecessary GPU runtime:

```powershell
.\pymk-ml-training\.venv\Scripts\python -m pip install "torch>=2.6,<3" --index-url https://download.pytorch.org/whl/cpu
.\pymk-ml-training\.venv\Scripts\python -m pip install -e "pymk-ml-training[heavy]"
```

Provide at least three [Day 24 v2 exports](DAY24_DATASETS.md), their sidecars,
the exact frozen profile transform, and a frozen L1 ONNX/metadata pair. Retain
the L1 metadata's original v1 input files at their recorded paths. The comparison
checks those files' hashes and requires identical snapshot/pair IDs, features,
acceptance labels, label windows and chronological partitions between v1 and v2.
Different cohorts or corrected acceptance labels require a new baseline experiment,
not an unfair comparison against old L1 data. Transform hashes/versions/cutoffs
are checked; no vocabulary, IDF or SVD refitting occurs in this trainer.

```powershell
.\pymk-ml-training\.venv\Scripts\python -m pymk_training.train_heavy_ranker `
  --input artifacts/training-v2/snapshot-1.csv `
  --input artifacts/training-v2/snapshot-2.csv `
  --input artifacts/training-v2/snapshot-3.csv `
  --embedding-transform artifacts/transform.json `
  --l1-model artifacts/models/profile-l1-v1/profile-l1-v1.onnx `
  --l1-metadata artifacts/models/profile-l1-v1/profile-l1-v1.metadata.json `
  --output-dir artifacts/models/heavy `
  --model-version heavy-mlp-v1
```

Use your actual immutable input paths and a fresh model version. The equivalent
installed entry point is `pymk-train-heavy-ranker`.

## Training and leakage controls

- Whole feature snapshots are split chronologically using the existing 70%/15%/
  remainder policy, with at least one snapshot per partition. Earlier label
  horizons cannot extend beyond the next partition's first feature timestamp.
- StandardScaler statistics are fitted only on training rows. Its mean and scale
  become PyTorch buffers and ONNX constants, so callers submit raw six-feature
  vectors in the existing `pair-v1` order; serving must not scale them again.
- The network is `6 → 16 ReLU → two independent linear/sigmoid heads` by default.
  Seed 42, one CPU thread, deterministic PyTorch algorithms, full-training-batch
  Adam, learning rate 0.01 and 200 epochs are defaults. Options allow a new
  version/experiment to specify seed, hidden size, learning rate and epoch budget.
- Both training targets must contain positive and negative rows. Loss is the
  unweighted mean binary cross entropy with logits over both targets. No class
  balancing, calibration fit, dropout or random row split is introduced.
- After each update, validation mean loss selects the best checkpoint; ties keep
  the earlier epoch. Test features/labels do not fit preprocessing, update weights
  or choose a checkpoint. Test metrics are computed after selection. Do not tune
  further using that report; new choices belong to a new validation experiment.
- The model restores caller RNG/thread/deterministic settings after training.
  Reproducible weights are tested within the same runtime. Cross-version/platform
  bitwise equality is not promised; retain input hashes and recorded runtimes.

This is a small full-batch CPU model, not a large-scale training system. More
complexity does not establish better recommendations or calibrated probabilities.

## ONNX contract and publication

The fixed trained network is serialized explicitly using standard ONNX
Sub/Div/Gemm/ReLU/Sigmoid operators, with weights and scaler buffers taken directly
from PyTorch. This avoids tracer/exporter-version dependencies. ONNX checker and
actual ONNX Runtime parity are mandatory before publication, including empty,
single-row and multi-row dynamic batches (absolute tolerance 1e-6, relative 1e-5).

| Tensor | Type/shape | Meaning |
|---|---|---|
| `features` | float32 `[N,6]` | Raw ordered `pair-v1` features |
| `invite_sent_probability` | float32 `[N,1]` | Directional unconditional invitation-sent outcome |
| `invite_accepted_probability` | float32 `[N,1]` | Directional unconditional acceptance outcome |

Both outcomes use `(featureAsOf,labelWindowEnd]`. Acceptance can refer to an
invitation sent before the window; outputs are not constrained by accepted ≤ sent.
Output bounds are `[0,1]`, but calibration evidence remains Day 27 work.

The ONNX uses opset 17 and IR version 8. Its versioned metadata records ordered
features, tensor names/shapes/semantics, SHA-256, runtime versions, seed, scaler,
hyperparameters, selected epoch, training/validation cutoffs, exact dataset
identities/provenance, embedding compatibility and inference parity error.

Publication refuses either existing output path. Each completed ONNX/JSON file
publishes without overwrite; the two files are not one filesystem transaction.
An interrupted pair is inactive and reserves its version. Use a new version for
another attempt; never repair publication by overwriting artifact bytes.

## Baseline comparison and interpretation

Metadata reports train/validation/test ROC AUC, average precision and log loss
for each heavy probability head. It compares acceptance with the exact frozen
L1 model and reports mutual-count ranking AUC/AP for both targets on identical
rows. Raw mutual counts are not probabilities, so no log loss is attributed to
that baseline. Single-class AUC/AP are `null`, never fabricated as zero or one.

L1 has no invitation-sent head; no L1 sent prediction is invented. Comparisons
are row-level offline measures within the exported candidate cohort, not an
A/B test, full-population recall or requester-grouped recommendation metrics.
Requester-level metrics, confidence intervals and calibration remain Day 27.
No automatic quality superiority or promotion claim follows from the report.

## Reproduce verification

```powershell
mvn verify
.\pymk-ml-training\.venv\Scripts\python -W error -m unittest discover -s pymk-ml-training/tests -v
.\scripts\day23-release-smoke.ps1 -IncludeHeavy
```

`-IncludeHeavy` implies v2 export validation. It stages fresh releases in isolated
containers, trains L1, trains/exports L2 and records frozen-cohort comparisons,
then verifies the still-L1 HTTP path, cache reuse and rollback. Its tiny fixture
is for contract correctness, not evidence of real-world model quality. Generated
evidence remains under ignored `artifacts/day23-smoke/<run>/`; the smoke stops
only its own API and containers. Tests also cover immutable publication, parity
failure, incompatible inputs, single-class data, reproducibility and the fact
that changed test features cannot change the selected checkpoint/scaler.

## Verification record — 2026-10-04

- Full `mvn verify`: all 12 modules successful; 128 Java tests with zero failures,
  errors or skips. The public application remains on its tested L1 serving path.
- Python unittest discovery with `-W error`: 33 tests passed, including the six
  new heavy-training tests. CPU PyTorch 2.14.1+cpu was used; runtime versions are
  recorded in every exported model's metadata.
- `pip check`, the installed training entry point and Docker Compose configuration
  validation passed.
- Extended smoke `98ed588a86` passed: fresh v1/v2 exports, real L1 and two-head
  PyTorch training, named ONNX parity, exact frozen-cohort comparisons, HTTP
  recommendation probabilities, cache reuse and two rollback transitions.
  This small fixture does not establish model-quality superiority or performance.
- Smoke containers and the API process were stopped; artifacts/reports remain
  under ignored `artifacts/day23-smoke/98ed588a86/`. No regular application
  database was changed by this verification.

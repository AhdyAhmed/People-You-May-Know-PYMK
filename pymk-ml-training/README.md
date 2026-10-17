# pymk-ml-training

Offline Python training project for PYMK models. Day 19 implements the L1
logistic-regression baseline and exports it to ONNX for Day 20 Java serving.

## Setup

```powershell
cd pymk-ml-training
python -m venv .venv
.venv\Scripts\python -m pip install --upgrade pip
.venv\Scripts\python -m pip install -e .
```

## Train the Day 19 light ranker

Supply at least three Day 18 exports with distinct `feature_as_of` snapshots.
The trainer keeps an entire snapshot in exactly one chronological partition;
it never performs a random row split.
Training label windows must end at or before the first validation snapshot,
and validation label windows at or before the first test snapshot. Overlaps
are rejected before training. Space snapshots at least as far apart as the
label horizon; the filenames below assume a horizon no longer than seven days.

```powershell
.venv\Scripts\python -m pymk_training.train_light_ranker `
  --input ..\artifacts\training\snapshot-2026-08-01.csv `
  --input ..\artifacts\training\snapshot-2026-08-08.csv `
  --input ..\artifacts\training\snapshot-2026-08-15.csv `
  --input ..\artifacts\training\snapshot-2026-08-22.csv `
  --output-dir ..\artifacts\models\light-ranker `
  --model-version light-logreg-v1
```

Outputs:

- `<model-version>.onnx`: `StandardScaler` plus logistic regression.
- `<model-version>.metadata.json`: feature order, temporal boundaries, metrics,
  verified training/validation label cutoffs, coefficients, input hashes,
  library versions, and ONNX SHA-256.

Both files are written through temporary files and atomically replaced. The
trainer rejects schema drift, duplicate snapshot/pair rows, invalid temporal
windows, non-binary labels, non-finite values, fewer than three snapshots, or
cross-partition label-window overlaps, or a training partition that lacks either class.

## Verify

```powershell
.venv\Scripts\python -m unittest discover -s tests -v
```

Generated datasets and model artifacts are intentionally ignored by Git.

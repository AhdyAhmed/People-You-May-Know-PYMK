# pymk-ml-training

Offline Python training project for PYMK models. Day 19 implements the L1
logistic-regression baseline and exports it to ONNX for Day 20 Java serving.
Day 25 adds a real two-head PyTorch heavy model and validated ONNX export;
the live API remains L0 → L1 until the scheduled L2 cutover.

## Setup

```powershell
cd pymk-ml-training
python -m venv .venv
.venv\Scripts\python -m pip install --upgrade pip
.venv\Scripts\python -m pip install "torch>=2.6,<3" --index-url https://download.pytorch.org/whl/cpu
.venv\Scripts\python -m pip install -e ".[heavy]"
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

Both files are written through temporary files and published individually using
exclusive atomic hard links. Existing model or metadata paths reserve the
version and are rejected before training; choose a new `--model-version` and
update the serving model/cache configuration together. Publication writes ONNX
first and metadata second; interruption can leave an incomplete inactive pair.
Retrain under a new version in that case. Both matching files and their checksum
are required by Java serving. The
trainer rejects schema drift, duplicate snapshot/pair rows, invalid temporal
windows, non-binary labels, non-finite values, fewer than three snapshots, or
cross-partition label-window overlaps, or a training partition that lacks either class.

## Day 22 profile embeddings

The CLI fits or reuses a **frozen TF-IDF + seeded SVD** transform over headline,
company, school and geography; member names/IDs are excluded. Vectors are
L2-normalized and zero-padded to 128 float32 dimensions. Blank/tokenless and
wholly unseen profiles produce zero vectors. Constant TF-IDF corpora (even with
different word order or punctuation) and one-term corpora use a stable TF-IDF
direction; other small corpora use bounded SVD dimensions.

Run this working four-member semantic fixture from this directory:

```powershell
.venv\Scripts\python -m pymk_training.profile_embeddings `
  --input tests\fixtures\profiles.csv `
  --fit --embedding-version profile-svd-v1 `
  --as-of 2026-01-03T00:00:00Z `
  --artifact ..\artifacts\embeddings\profile-svd-v1.transform.json `
  --output ..\artifacts\embeddings\profile-svd-v1.vectors.csv

.venv\Scripts\python -m pymk_training.profile_embeddings `
  --input tests\fixtures\profiles.csv `
  --as-of 2026-01-04T00:00:00Z `
  --artifact ..\artifacts\embeddings\profile-svd-v1.transform.json `
  --output ..\artifacts\embeddings\profile-svd-v1.next.vectors.csv
```

Input columns: `member_id,headline,company,school,geo_region,created_at,profile_as_of`.
IDs must be positive and unique; timestamps must carry a timezone. The explicit
`profile_as_of` means the profile was available at that timestamp. Supply genuine
archived profiles for historical fitting: today's mutable member table cannot
reconstruct yesterday's profile. Not-yet-created members are excluded; a future
profile observation is rejected rather than silently leaking future information.
Transform fit cutoff must not exceed the requested vector availability timestamp.
Duplicate headers and rows with missing/extra CSV fields are rejected. On reuse,
an optional `--embedding-version` must match the artifact rather than relabeling it.

The portable JSON records text policy, vocabulary/IDF/components, fit cutoff,
corpus hash, seed, dimension policy, version and scikit-learn version; its checksum
is checked on reload along with required metadata, supported policies, vocabulary
indices and the exact matrix shape. There is no pickle/code execution. Exports use a completed
temporary file then an atomic, no-overwrite local hard link (NTFS/ext4 supported).
Existing output paths are refused; use a new version/path. A fit creates two
individually complete files, not a transaction spanning both: if vector publication
fails after the artifact publishes, reuse that frozen artifact without `--fit`.

Vector CSV columns match V6 database provenance: `member_id,embedding,updated_at,
embedding_version,fit_cutoff`. JSON-array vectors are compatible with pgvector.
These are **staging exports**, not live database mutations. Day 23 owns complete
generation validation, coordinated activation, compatible feature refresh and
cache/model cutover; do not manually overwrite live embeddings under an old L1
model. The Java retrieval and pair-feature paths already understand the new
provenance and neutral-vector policy. Existing synthetic seeding remains
`placeholder-v1` until that coordinated cutover.

## Day 23 release-compatible training

Use `python -m pymk_training.release_manifest` to bind an immutable vector CSV
and frozen transform to a release ID. The Java `embeddingRefreshJob` stages that
generation and computes compatible features without changing serving.
`servingReleaseJob action=export` produces registered CSV/provenance pairs.
Pass `--embedding-transform <transform.json>` to the existing L1 trainer for
these datasets; it verifies provenance and emits the compatibility metadata
required for managed activation. Plain Day 18 datasets remain supported for
legacy training but cannot pass the managed-release promotion gate.

See [the complete runbook](../docs/DAY23_RELEASES.md) and
`scripts/day23-release-smoke.ps1` for actual training, activation and rollback.

## Day 24 multi-target datasets

`servingReleaseJob action=export-v2` creates release-bound CSVs with
`schema_version=2`, `invite_sent` and `invite_accepted`, plus mandatory provenance
sidecars. Both targets are directional unconditional outcomes in the same mature
future window; acceptance without an in-window invitation is valid.

```powershell
.venv\Scripts\python -m pymk_training.dataset_v2 --input ../artifacts/v2-1.csv --input ../artifacts/v2-2.csv --input ../artifacts/v2-3.csv
```

The validator checks schema, CSV/cohort hashes, source/embedding lineage and
chronological partitions with non-overlapping label horizons. Programmatic
`load_multitarget_datasets` returns two label columns ordered sent, accepted.
The existing L1 loader/trainer deliberately rejects v2 inputs; the Day 25 heavy
trainer consumes them. See [the full v2 runbook](../docs/DAY24_DATASETS.md) for export,
correction and archive rules. Run the repository smoke script with `-IncludeV2`
to verify Java exports against this Python reader and exercise serving rollback.

## Day 25 heavy training

Use `python -m pymk_training.train_heavy_ranker` (or `pymk-train-heavy-ranker`)
with repeated `--input` v2 exports, `--embedding-transform`, `--l1-model`,
`--l1-metadata`, `--output-dir` and a new `--model-version`.
It trains a seeded CPU MLP with two independent heads, fits scaling only on
training data, selects a checkpoint using validation loss and publishes immutable
ONNX/metadata after real PyTorch/ONNX parity checks. The frozen L1 input files
must remain available for exact cohort comparison. See the
[complete training runbook](../docs/DAY25_HEAVY_TRAINING.md) for runnable commands,
named tensor contracts, artifact rules and metric limitations.

The smoke script's `-IncludeHeavy` switch trains both models on fresh Java exports
and then verifies the unchanged L1 HTTP/rollback path. Java L2 serving remains
Day 26; heavy training never changes the active application release.

## Verify

```powershell
.venv\Scripts\python -m unittest discover -s tests -v
```

Generated datasets and model artifacts are intentionally ignored by Git.

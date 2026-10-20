# Day 24 — versioned multi-target training data

Day 24 implements data preparation for the future heavy ranker. It does **not**
activate L2 or change the public L0 → L1 response contract. The existing Day 18
CSV, Day 19 trainer and Day 23 `action=export` remain v1 and compatible.

## Label contract

For each directional pair `(member_id, candidate_id)` in one completed release:

- `invite_sent = 1` if at least one `INVITE_SENT` event has that actor/target pair
  in `(feature_as_of, label_window_end]`; otherwise 0.
- `invite_accepted = 1` uses the same rule for `INVITE_ACCEPTED`.
- Both are **unconditional window outcomes**. Acceptance can refer to an invitation
  sent before the feature snapshot; `(invite_sent=0, invite_accepted=1)` is valid.
  Neither label implies the other. Duplicate events do not increase a label.
- Reversed pairs, other event types, events at the exclusive lower boundary and
  events after the inclusive upper boundary do not count.
- `label_window_end` must be after the feature snapshot and no later than the
  batch clock. Labels use future events; features remain the frozen release's
  six snapshot-safe values, never recomputed from those future signals.

The UTF-8 CSV has this exact header and `schema_version=2` on every row:

```text
schema_version,member_id,candidate_id,mutual_connection_count,same_company,same_school,same_geo,embedding_cosine_sim,profile_view_count_last_30d,observation_window_start,feature_as_of,label_window_end,invite_sent,invite_accepted
```

Rows are ordered by member/candidate IDs. The observation window starts 30 days
before `feature_as_of`, matching the existing `pair-v1` feature definition. No
negative sampling is introduced: every materialized candidate pair is exported.
This is a candidate cohort, not an all-member-pairs population or exposure log.

## Export a named frozen release

First stage historical releases using the [Day 23 workflow](DAY23_RELEASES.md).
Use one frozen embedding transform fitted no later than the earliest snapshot.
Each release must be STAGED or READY; it need not be the active serving release.
The job pins the named release with a database row lock and holds source-table
SHARE locks while streaming labels, so publication cannot mix source revisions.
It does not move the active serving pointer or invalidate recommendation caches.

From the repository root:

```powershell
java -jar pymk-batch/target/pymk-batch.jar --spring.batch.job.name=servingReleaseJob action=export-v2 releaseId=profile-history-1 datasetId=multi-target-20260103-v1 labelWindowEnd=2026-01-10T00:00:00Z outputPath=artifacts/training-v2/snapshot-1.csv
```

Use the real release ID, a new dataset ID and a mature window for your data.
The job produces the CSV and `<CSV>.provenance.json`, then registers the dataset
in V8's `pymk_training_datasets_v2`. It does not register v2 hashes in the v1
`pymk_release_exports` table; v2 data cannot accidentally pass L1 promotion gates.
A partial or empty release, immature window, reused identity or existing output
path fails rather than overwriting an archive. Concurrent publishers of the same
ID are serialized, and only one can register and publish it.

The sidecar and registry retain:

- Dataset ID, schema version, exact target order/semantics and `pair-v1` contract.
- CSV SHA-256, row count and cohort SHA-256. The cohort hash is SHA-256 of sorted
  ASCII `member_id,candidate_id\n` pairs; CSV hashes also bind features and labels.
- Release ID, immutable release-manifest hash, embedding version, transform hash
  and fit cutoff.
- Feature snapshot, label-window end, export time, feature-source revision and
  label-source revision. Source revisions are database-local mutation counters,
  not portable content hashes or a historical event store.

Paths and IDs are immutable. CSV and sidecar publish individually without
overwrite, but the filesystem and registry are not one atomic transaction.
An interrupted publication can leave inactive files; choose new paths/identity
for a fresh attempt and retain only complete registered pairs for experiments.
Preserve registry tables in database backups. Never manually edit archived CSVs
or sidecars to repair a failed checksum.

## Validate and partition in Python

Create at least three releases at distinct feature snapshots, then export each
after its label window matures. Keep train/validation label horizons no later
than the next partition's first feature timestamp; random row splits are forbidden.

```powershell
.\pymk-ml-training\.venv\Scripts\python -m pymk_training.dataset_v2 --input artifacts/training-v2/snapshot-1.csv --input artifacts/training-v2/snapshot-2.csv --input artifacts/training-v2/snapshot-3.csv
```

This verifies hashes, required provenance, schemas, row widths, finite bounded
features, binary targets, unique snapshot/pair keys, cohort counts/hashes, mature
window metadata and compatible frozen transforms. It reports whole-snapshot
chronological train/validation/test partitions plus input identities/hashes.
The existing ratios (70%/15%/remainder, with at least one snapshot per partition)
apply. Overlapping label horizons are rejected, not silently purged or shuffled.

Programmatic consumers use `load_multitarget_datasets(paths)` and
`temporal_split(dataset)` from `pymk_training.dataset`. `dataset.labels` has
shape `[rows, 2]`, ordered **sent, accepted**. The v1 `load_datasets` intentionally
rejects v2 headers, so the L1 trainer cannot silently choose the wrong target.
The offline validator checks archive integrity, not registry authenticity; keep
the paired registry records for future model-promotion verification.

## Event time versus knowledge time

The current event schema records `occurred_at`, not when the system learned an
event. Export therefore reconstructs **event-time outcomes known at export time**,
not knowledge available at the original snapshot. A mature window does not prove
that every late event has arrived. Late/corrected labels require a **new dataset
ID**, output files and evaluation report. The previous archive remains unchanged;
the new label-source revision records that source state changed. Frozen features
continue to describe the original release, not a silently rebuilt profile state.
Historical mutable-profile reconstruction, ingestion-time lineage and continuous
refresh/reconciliation remain M6/M7 work.

## Reproducible verification

```powershell
mvn verify
.\pymk-ml-training\.venv\Scripts\python -W error -m unittest discover -s pymk-ml-training/tests -v
.\scripts\day23-release-smoke.ps1 -IncludeV2
```

The extended smoke creates isolated disposable PostgreSQL/Redis containers,
exports three real v2 snapshots, validates them and their chronological split in
Python, then trains v1 L1 and checks model activation, HTTP serving, cache reuse
and two rollback transitions. It leaves generated evidence under ignored
`artifacts/day23-smoke/<run>/` and stops only its own API process/containers.
Automated tests also cover boundaries, directions, duplicates, missing outcomes,
acceptance-only labels, future-feature exclusion, corrections, immutability,
concurrent publication, malformed input and split leakage. L2 training starts on
Day 25; passing these checks is not a guarantee against every possible defect.

## Verification record — completed 2026-10-04

- Full `mvn verify`: all 12 reactor modules successful; 128 Java tests, no
  failures, errors or skips (build completed 2026-10-03).
- Python discovery with `-W error`: 27 tests passed, including v1 regression
  coverage, v2 provenance/row validation and temporal-boundary rejection.
- Docker Compose configuration validation passed.
- Extended smoke run `69f4aa1c51` passed on 2026-10-04: three registered v2
  exports (18 rows total) loaded and partitioned in Python, real v1 model
  training/activation, database-only model startup, HTTP probability ordering,
  cache reuse and two rollback transitions. This tiny fixture verifies contracts,
  not real-world model quality or production performance.
- Smoke containers and the API process were stopped after verification; evidence
  remains under ignored `artifacts/day23-smoke/69f4aa1c51/`. The regular local
  application database was not modified by the smoke test.

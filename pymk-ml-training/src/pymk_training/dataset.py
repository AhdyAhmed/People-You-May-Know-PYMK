"""Strict v1/v2 dataset loading and snapshot-level chronological splitting."""

from __future__ import annotations

import csv
import hashlib
import json
import math
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Iterable

import numpy as np

CSV_COLUMNS = (
    "member_id",
    "candidate_id",
    "mutual_connection_count",
    "same_company",
    "same_school",
    "same_geo",
    "embedding_cosine_sim",
    "profile_view_count_last_30d",
    "observation_window_start",
    "feature_as_of",
    "label_window_end",
    "label",
)

FEATURE_NAMES = (
    "mutual_connection_count",
    "same_company",
    "same_school",
    "same_geo",
    "embedding_cosine_sim",
    "profile_view_count_last_30d",
)

V2_COLUMNS = ("schema_version",) + CSV_COLUMNS[:-1] + ("invite_sent", "invite_accepted")


class DatasetValidationError(ValueError):
    """Raised when an input cannot safely be used for temporal training."""


@dataclass(frozen=True)
class Dataset:
    features: np.ndarray
    labels: np.ndarray
    feature_as_of: np.ndarray
    label_window_end: np.ndarray
    member_ids: np.ndarray
    candidate_ids: np.ndarray
    input_files: tuple[dict[str, object], ...]

    @property
    def row_count(self) -> int:
        return int(self.labels.shape[0])


@dataclass(frozen=True)
class TemporalSplit:
    train: np.ndarray
    validation: np.ndarray
    test: np.ndarray
    train_snapshots: tuple[str, ...]
    validation_snapshots: tuple[str, ...]
    test_snapshots: tuple[str, ...]


def load_datasets(paths: Iterable[Path]) -> Dataset:
    return _load_datasets(paths, v2=False)


def load_multitarget_datasets(paths: Iterable[Path]) -> Dataset:
    """Load v2 only; labels[:, 0] is sent, labels[:, 1] is accepted."""
    return _load_datasets(paths, v2=True)


def _load_datasets(paths: Iterable[Path], *, v2: bool) -> Dataset:
    resolved = tuple(Path(path).expanduser().resolve() for path in paths)
    if not resolved:
        raise DatasetValidationError("at least one --input CSV is required")

    parsed_rows: list[tuple[datetime, int, int, list[float], int | list[int], datetime]] = []
    fingerprints: list[dict[str, object]] = []
    seen_keys: set[tuple[str, int, int]] = set()
    snapshot_windows: dict[str, tuple[str, str]] = {}
    dataset_ids: set[str] = set()
    compatibility = None

    for path in resolved:
        if not path.is_file():
            raise DatasetValidationError(f"input CSV does not exist: {path}")
        fingerprints.append({
            "path": str(path),
            "sha256": _sha256(path),
            "bytes": path.stat().st_size,
        })
        provenance = None
        file_pairs = []
        if v2:
            provenance = _v2_provenance(path, fingerprints[-1]["sha256"])
            if provenance["datasetId"] in dataset_ids:
                raise DatasetValidationError("duplicate dataset identity")
            dataset_ids.add(provenance["datasetId"])
            identity = (provenance["embeddingVersion"], provenance["transformSha256"],
                        _canonical_instant(_instant(provenance["fitCutoff"])))
            if compatibility is not None and compatibility != identity:
                raise DatasetValidationError("v2 datasets use incompatible embedding transforms")
            compatibility = identity
            fingerprints[-1]["provenance"] = provenance
        with path.open("r", encoding="utf-8", newline="") as handle:
            reader = csv.DictReader(handle)
            columns = V2_COLUMNS if v2 else CSV_COLUMNS
            if tuple(reader.fieldnames or ()) != columns:
                raise DatasetValidationError(
                    f"unexpected CSV schema in {path}; expected {','.join(columns)}")
            for line_number, row in enumerate(reader, start=2):
                try:
                    if None in row or any(value is None for value in row.values()):
                        raise DatasetValidationError("CSV row width differs from header")
                    member_id = _positive_int(row["member_id"], "member_id")
                    candidate_id = _positive_int(row["candidate_id"], "candidate_id")
                    if member_id == candidate_id:
                        raise DatasetValidationError("member_id and candidate_id must differ")
                    observation_start = _instant(row["observation_window_start"])
                    feature_as_of = _instant(row["feature_as_of"])
                    label_window_end = _instant(row["label_window_end"])
                    if not observation_start < feature_as_of < label_window_end:
                        raise DatasetValidationError(
                            "observation_window_start < feature_as_of < label_window_end is required")
                    snapshot = _canonical_instant(feature_as_of)
                    windows = (
                        _canonical_instant(observation_start),
                        _canonical_instant(label_window_end),
                    )
                    existing_windows = snapshot_windows.setdefault(snapshot, windows)
                    if existing_windows != windows:
                        raise DatasetValidationError(
                            f"snapshot {snapshot} has inconsistent observation or label windows")
                    key = (snapshot, member_id, candidate_id)
                    if key in seen_keys:
                        raise DatasetValidationError(
                            f"duplicate snapshot/pair row: {snapshot}, {member_id}, {candidate_id}")
                    seen_keys.add(key)
                    if v2:
                        if row["schema_version"] != "2":
                            raise DatasetValidationError("schema_version must be 2")
                        if (feature_as_of != _instant(provenance["featureAsOf"])
                                or label_window_end != _instant(provenance["labelWindowEnd"])):
                            raise DatasetValidationError("row windows differ from provenance")
                        if observation_start != feature_as_of - timedelta(days=30):
                            raise DatasetValidationError("pair-v1 observation window must span 30 days")
                        label = [_binary_int(row[name], name) for name in ("invite_sent", "invite_accepted")]
                        file_pairs.append((member_id, candidate_id))
                    else:
                        label = _binary_int(row["label"], "label")
                    values = [
                        float(_nonnegative_int(
                            row["mutual_connection_count"], "mutual_connection_count")),
                        _boolean(row["same_company"], "same_company"),
                        _boolean(row["same_school"], "same_school"),
                        _boolean(row["same_geo"], "same_geo"),
                        _bounded_float(
                            row["embedding_cosine_sim"], "embedding_cosine_sim", -1.0, 1.0),
                        float(_nonnegative_int(
                            row["profile_view_count_last_30d"],
                            "profile_view_count_last_30d")),
                    ]
                    parsed_rows.append(
                        (feature_as_of, member_id, candidate_id, values, label, label_window_end))
                except (DatasetValidationError, ValueError) as exception:
                    raise DatasetValidationError(
                        f"{path}:{line_number}: {exception}") from exception
        if v2:
            cohort = hashlib.sha256("".join(f"{m},{c}\n" for m, c in sorted(file_pairs)).encode()).hexdigest()
            if (not file_pairs or len(file_pairs) != provenance["rowCount"]
                    or cohort != provenance["cohortSha256"] or _sha256(path) != fingerprints[-1]["sha256"]):
                raise DatasetValidationError("v2 row count, cohort or CSV checksum mismatch")

    if not parsed_rows:
        raise DatasetValidationError("training dataset contains no rows")
    parsed_rows.sort(key=lambda item: (item[0], item[1], item[2]))
    features = np.asarray([item[3] for item in parsed_rows], dtype=np.float32)
    if not np.isfinite(features).all():
        raise DatasetValidationError("feature matrix contains a non-finite value")
    return Dataset(
        features=features,
        labels=np.asarray([item[4] for item in parsed_rows], dtype=np.int64),
        feature_as_of=np.asarray(
            [_canonical_instant(item[0]) for item in parsed_rows], dtype=str),
        label_window_end=np.asarray(
            [_canonical_instant(item[5]) for item in parsed_rows], dtype=str),
        member_ids=np.asarray([item[1] for item in parsed_rows], dtype=np.int64),
        candidate_ids=np.asarray([item[2] for item in parsed_rows], dtype=np.int64),
        input_files=tuple(fingerprints),
    )


def temporal_split(
    dataset: Dataset,
    train_ratio: float = 0.70,
    validation_ratio: float = 0.15,
) -> TemporalSplit:
    if not 0.0 < train_ratio < 1.0:
        raise DatasetValidationError("train ratio must be between 0 and 1")
    if not 0.0 < validation_ratio < 1.0 or train_ratio + validation_ratio >= 1.0:
        raise DatasetValidationError(
            "validation ratio must be positive and leave a non-empty test ratio")

    snapshots = tuple(sorted(set(dataset.feature_as_of.tolist())))
    if len(snapshots) < 3:
        raise DatasetValidationError(
            "at least three distinct feature_as_of snapshots are required for temporal splits")
    train_count = min(max(1, math.floor(len(snapshots) * train_ratio)), len(snapshots) - 2)
    remaining = len(snapshots) - train_count
    validation_count = min(
        max(1, math.floor(len(snapshots) * validation_ratio)), remaining - 1)

    train_snapshots = snapshots[:train_count]
    validation_snapshots = snapshots[train_count:train_count + validation_count]
    test_snapshots = snapshots[train_count + validation_count:]
    # A chronological feature split is insufficient if earlier labels include
    # outcomes that were still unknown at the next partition's first snapshot.
    for name, earlier_snapshots, next_start in (
        ("training", train_snapshots, validation_snapshots[0]),
        ("validation", validation_snapshots, test_snapshots[0]),
    ):
        mask = np.isin(dataset.feature_as_of, earlier_snapshots)
        label_end = max(dataset.label_window_end[mask].tolist())
        if label_end > next_start:
            raise DatasetValidationError(
                f"{name} label window ends at {label_end}, after the next partition "
                f"starts at {next_start}; use snapshots spaced beyond the label "
                "horizon or adjust split ratios to create non-overlapping windows")
    return TemporalSplit(
        train=np.flatnonzero(np.isin(dataset.feature_as_of, train_snapshots)),
        validation=np.flatnonzero(np.isin(dataset.feature_as_of, validation_snapshots)),
        test=np.flatnonzero(np.isin(dataset.feature_as_of, test_snapshots)),
        train_snapshots=train_snapshots,
        validation_snapshots=validation_snapshots,
        test_snapshots=test_snapshots,
    )


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _v2_provenance(path: Path, csv_hash: str) -> dict:
    try:
        metadata = json.loads(Path(str(path) + ".provenance.json").read_text(encoding="utf-8"))
        if (metadata["schemaVersion"] != 2 or metadata["csvSha256"] != csv_hash
                or metadata["featureSchema"] != "pair-v1"
                or metadata["targets"] != ["invite_sent", "invite_accepted"]
                or metadata["labelSemantics"] != "directional-unconditional-(featureAsOf,labelWindowEnd]"):
            raise DatasetValidationError("invalid v2 schema, targets or checksum")
        for key in ("datasetId", "releaseId", "embeddingVersion"):
            value = metadata[key]
            if not isinstance(value, str) or not value.strip():
                raise DatasetValidationError(f"missing {key}")
        for key in ("csvSha256", "transformSha256", "cohortSha256", "releaseManifestSha256"):
            value = metadata[key]
            if not isinstance(value, str) or len(value) != 64 or any(c not in "0123456789abcdef" for c in value):
                raise DatasetValidationError(f"invalid {key}")
        for key in ("featureSourceRevision", "labelSourceRevision"):
            if not isinstance(metadata[key], str) or not metadata[key].isdigit():
                raise DatasetValidationError(f"invalid {key}")
        if int(metadata["labelSourceRevision"]) < int(metadata["featureSourceRevision"]):
            raise DatasetValidationError("label source revision predates feature materialization")
        if type(metadata["rowCount"]) is not int or metadata["rowCount"] <= 0:
            raise DatasetValidationError("invalid rowCount")
        if not (_instant(metadata["fitCutoff"]) <= _instant(metadata["featureAsOf"])
                < _instant(metadata["labelWindowEnd"]) <= _instant(metadata["exportedAt"])):
            raise DatasetValidationError("invalid provenance time ordering or immature labels")
        return metadata
    except (OSError, KeyError, TypeError, ValueError, AttributeError) as error:
        raise DatasetValidationError(f"invalid v2 provenance for {path}: {error}") from error


def _instant(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise DatasetValidationError("timestamps must include a UTC offset")
    return parsed


def _canonical_instant(value: datetime) -> str:
    return (value.astimezone(timezone.utc)
            .isoformat(timespec="microseconds")
            .replace("+00:00", "Z"))


def _positive_int(value: str, name: str) -> int:
    parsed = int(value)
    if not 0 < parsed <= 2**63 - 1:
        raise DatasetValidationError(f"{name} must be a positive signed 64-bit integer")
    return parsed


def _nonnegative_int(value: str, name: str) -> int:
    parsed = int(value)
    if not 0 <= parsed <= 2**31 - 1:
        raise DatasetValidationError(f"{name} must be a nonnegative signed 32-bit integer")
    return parsed


def _binary_int(value: str, name: str) -> int:
    parsed = int(value)
    if parsed not in (0, 1):
        raise DatasetValidationError(f"{name} must be 0 or 1")
    return parsed


def _boolean(value: str, name: str) -> float:
    normalized = value.strip().lower()
    if normalized == "true":
        return 1.0
    if normalized == "false":
        return 0.0
    raise DatasetValidationError(f"{name} must be true or false")


def _bounded_float(value: str, name: str, minimum: float, maximum: float) -> float:
    parsed = float(value)
    if not math.isfinite(parsed) or not minimum <= parsed <= maximum:
        raise DatasetValidationError(f"{name} must be finite and in [{minimum}, {maximum}]")
    return parsed

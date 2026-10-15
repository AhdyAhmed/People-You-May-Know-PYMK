from __future__ import annotations

import csv
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

import onnx

from pymk_training.dataset import (
    CSV_COLUMNS,
    DatasetValidationError,
    load_datasets,
    temporal_split,
)
from pymk_training.train_light_ranker import train_and_export


class LightRankerTrainingTest(unittest.TestCase):

    def test_snapshot_groups_never_cross_temporal_partitions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            inputs = _write_snapshots(Path(directory), snapshot_count=6)
            dataset = load_datasets(inputs)
            split = temporal_split(dataset)

            train = set(dataset.feature_as_of[split.train].tolist())
            validation = set(dataset.feature_as_of[split.validation].tolist())
            test = set(dataset.feature_as_of[split.test].tolist())
            self.assertFalse(train & validation)
            self.assertFalse(train & test)
            self.assertFalse(validation & test)
            self.assertLess(max(train), min(validation))
            self.assertLess(max(validation), min(test))

    def test_trainer_exports_valid_onnx_and_reproducible_contract(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inputs = _write_snapshots(root / "data", snapshot_count=8)
            onnx_path, metadata_path, metadata = train_and_export(
                inputs, root / "models", "light-logreg-test")

            self.assertTrue(onnx_path.is_file())
            self.assertTrue(metadata_path.is_file())
            onnx.checker.check_model(onnx.load(onnx_path))
            persisted = json.loads(metadata_path.read_text(encoding="utf-8"))
            self.assertEqual(metadata["onnx"]["sha256"], persisted["onnx"]["sha256"])
            self.assertEqual(
                "chronological-feature-snapshot", persisted["split"]["strategy"])
            self.assertEqual(32, persisted["rowCount"])
            self.assertEqual(6, len(persisted["features"]))
            self.assertEqual(6, len(persisted["parameters"]["coefficients"]))
            self.assertFalse(list((root / "models").glob("*.tmp")))

    def test_rejects_duplicate_pairs_and_too_few_snapshots(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inputs = _write_snapshots(root, snapshot_count=2)
            dataset = load_datasets(inputs)
            with self.assertRaisesRegex(DatasetValidationError, "at least three"):
                temporal_split(dataset)

            with self.assertRaisesRegex(DatasetValidationError, "duplicate snapshot/pair"):
                load_datasets([inputs[0], inputs[0]])


def _write_snapshots(directory: Path, snapshot_count: int) -> list[Path]:
    directory.mkdir(parents=True, exist_ok=True)
    first_snapshot = datetime(2026, 1, 1, tzinfo=timezone.utc)
    outputs: list[Path] = []
    for snapshot_index in range(snapshot_count):
        feature_as_of = first_snapshot + timedelta(days=7 * snapshot_index)
        observation_start = feature_as_of - timedelta(days=30)
        label_end = feature_as_of + timedelta(days=7)
        path = directory / f"snapshot-{snapshot_index:02d}.csv"
        with path.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=CSV_COLUMNS)
            writer.writeheader()
            for pair_index in range(4):
                writer.writerow({
                    "member_id": 100 + pair_index,
                    "candidate_id": 200 + pair_index,
                    "mutual_connection_count": snapshot_index + pair_index,
                    "same_company": str(pair_index % 2 == 0).lower(),
                    "same_school": str(pair_index % 3 == 0).lower(),
                    "same_geo": str(pair_index % 2 == 1).lower(),
                    "embedding_cosine_sim": -0.6 + 0.3 * pair_index,
                    "profile_view_count_last_30d": pair_index * 2,
                    "observation_window_start": _instant(observation_start),
                    "feature_as_of": _instant(feature_as_of),
                    "label_window_end": _instant(label_end),
                    "label": 1 if pair_index in (1, 3) else 0,
                })
        outputs.append(path)
    return outputs


def _instant(value: datetime) -> str:
    return value.isoformat().replace("+00:00", "Z")


if __name__ == "__main__":
    unittest.main()

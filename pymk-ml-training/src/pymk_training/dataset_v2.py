"""Validate immutable Day 24 exports and report leakage-safe temporal partitions."""
import argparse
import json
from pathlib import Path

from pymk_training.dataset import load_multitarget_datasets, temporal_split


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", action="append", required=True, type=Path)
    args = parser.parse_args()
    dataset = load_multitarget_datasets(args.input)
    split = temporal_split(dataset)
    print(json.dumps({"schemaVersion": 2, "rows": dataset.row_count,
                      "targets": ["invite_sent", "invite_accepted"],
                      "trainSnapshots": split.train_snapshots,
                      "validationSnapshots": split.validation_snapshots,
                      "testSnapshots": split.test_snapshots,
                      "inputs": dataset.input_files}, indent=2))


if __name__ == "__main__":
    main()

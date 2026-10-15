"""Train and atomically export the Day 19 L1 logistic-regression model."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

import numpy as np
import onnx
import onnxruntime
import sklearn
import skl2onnx
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    accuracy_score,
    average_precision_score,
    log_loss,
    roc_auc_score,
)
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

from pymk_training.dataset import (
    FEATURE_NAMES,
    Dataset,
    DatasetValidationError,
    TemporalSplit,
    load_datasets,
    temporal_split,
)

MODEL_VERSION_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")


def train_and_export(
    inputs: Sequence[Path],
    output_dir: Path,
    model_version: str,
    train_ratio: float = 0.70,
    validation_ratio: float = 0.15,
) -> tuple[Path, Path, dict[str, object]]:
    if MODEL_VERSION_PATTERN.fullmatch(model_version) is None:
        raise DatasetValidationError(
            "model version must be 1-128 filename-safe letters, digits, dots, underscores, or hyphens")
    dataset = load_datasets(inputs)
    split = temporal_split(dataset, train_ratio, validation_ratio)
    train_labels = dataset.labels[split.train]
    if set(train_labels.tolist()) != {0, 1}:
        raise DatasetValidationError(
            "training partition must contain both positive and negative labels")

    classifier = LogisticRegression(
        class_weight="balanced",
        max_iter=2_000,
        random_state=42,
        solver="lbfgs",
    )
    pipeline = Pipeline([
        ("scaler", StandardScaler()),
        ("classifier", classifier),
    ])
    pipeline.fit(dataset.features[split.train], train_labels)

    onnx_model = convert_sklearn(
        pipeline,
        name=model_version,
        initial_types=[("features", FloatTensorType([None, len(FEATURE_NAMES)]))],
        options={id(classifier): {"zipmap": False}},
        target_opset=17,
    )
    onnx.checker.check_model(onnx_model)

    output_dir = Path(output_dir).expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    onnx_path = output_dir / f"{model_version}.onnx"
    metadata_path = output_dir / f"{model_version}.metadata.json"
    onnx_bytes = onnx_model.SerializeToString()
    onnx_sha256 = hashlib.sha256(onnx_bytes).hexdigest()
    _verify_onnx_predictions(onnx_bytes, pipeline, dataset.features)

    scaler = pipeline.named_steps["scaler"]
    fitted_classifier = pipeline.named_steps["classifier"]
    metadata: dict[str, object] = {
        "schemaVersion": 1,
        "modelVersion": model_version,
        "modelType": "standard-scaler+logistic-regression",
        "createdAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "randomSeed": 42,
        "features": list(FEATURE_NAMES),
        "inputTensor": {
            "name": "features",
            "dtype": "float32",
            "shape": [None, len(FEATURE_NAMES)],
        },
        "positiveClass": 1,
        "rowCount": dataset.row_count,
        "positiveRate": float(np.mean(dataset.labels)),
        "inputs": list(dataset.input_files),
        "split": _split_metadata(split),
        "metrics": {
            "train": _metrics(pipeline, dataset, split.train),
            "validation": _metrics(pipeline, dataset, split.validation),
            "test": _metrics(pipeline, dataset, split.test),
        },
        "parameters": {
            "classWeight": "balanced",
            "solver": "lbfgs",
            "maxIterations": 2_000,
            "scalerMean": scaler.mean_.tolist(),
            "scalerScale": scaler.scale_.tolist(),
            "coefficients": fitted_classifier.coef_[0].tolist(),
            "intercept": float(fitted_classifier.intercept_[0]),
        },
        "runtime": {
            "python": platform.python_version(),
            "numpy": np.__version__,
            "scikitLearn": sklearn.__version__,
            "skl2onnx": skl2onnx.__version__,
            "onnx": onnx.__version__,
            "onnxRuntime": onnxruntime.__version__,
        },
        "onnx": {"opset": 17, "sha256": onnx_sha256},
    }

    _atomic_write(onnx_path, onnx_bytes)
    _atomic_write(
        metadata_path,
        (json.dumps(metadata, indent=2, sort_keys=True, allow_nan=False) + "\n")
        .encode("utf-8"),
    )
    return onnx_path, metadata_path, metadata


def _metrics(model: Pipeline, dataset: Dataset, indices: np.ndarray) -> dict[str, object]:
    labels = dataset.labels[indices]
    probabilities = model.predict_proba(dataset.features[indices])[:, 1]
    predictions = (probabilities >= 0.5).astype(np.int64)
    both_classes = len(set(labels.tolist())) == 2
    return {
        "rows": int(indices.size),
        "positives": int(np.sum(labels)),
        "positiveRate": float(np.mean(labels)),
        "accuracyAt0_5": float(accuracy_score(labels, predictions)),
        "logLoss": float(log_loss(labels, probabilities, labels=[0, 1])),
        "rocAuc": float(roc_auc_score(labels, probabilities)) if both_classes else None,
        "averagePrecision": (
            float(average_precision_score(labels, probabilities)) if both_classes else None
        ),
    }


def _split_metadata(split: TemporalSplit) -> dict[str, object]:
    return {
        "strategy": "chronological-feature-snapshot",
        "train": _partition(split.train, split.train_snapshots),
        "validation": _partition(split.validation, split.validation_snapshots),
        "test": _partition(split.test, split.test_snapshots),
    }


def _partition(indices: np.ndarray, snapshots: tuple[str, ...]) -> dict[str, object]:
    return {
        "rows": int(indices.size),
        "snapshots": len(snapshots),
        "fromInclusive": snapshots[0],
        "throughInclusive": snapshots[-1],
    }


def _verify_onnx_predictions(
    onnx_bytes: bytes, model: Pipeline, features: np.ndarray
) -> None:
    session = onnxruntime.InferenceSession(
        onnx_bytes, providers=["CPUExecutionProvider"])
    outputs = session.run(None, {"features": features.astype(np.float32, copy=False)})
    if len(outputs) != 2 or not isinstance(outputs[1], np.ndarray) or outputs[1].shape != (features.shape[0], 2):
        raise RuntimeError("exported ONNX model has an unexpected output contract")
    expected = model.predict_proba(features)
    if not np.allclose(outputs[1], expected, rtol=1.0e-5, atol=1.0e-6):
        raise RuntimeError("exported ONNX probabilities differ from the trained model")


def _atomic_write(path: Path, content: bytes) -> None:
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_path, path)
    except BaseException:
        temporary_path.unlink(missing_ok=True)
        raise


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Train the leakage-safe PYMK L1 logistic-regression model")
    parser.add_argument(
        "--input", action="append", required=True, type=Path,
        help="Day 18 CSV path; repeat for multiple feature snapshots")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--model-version", default="light-logreg-v1")
    parser.add_argument("--train-ratio", type=float, default=0.70)
    parser.add_argument("--validation-ratio", type=float, default=0.15)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        onnx_path, metadata_path, metadata = train_and_export(
            inputs=arguments.input,
            output_dir=arguments.output_dir,
            model_version=arguments.model_version,
            train_ratio=arguments.train_ratio,
            validation_ratio=arguments.validation_ratio,
        )
    except (DatasetValidationError, OSError) as exception:
        print(f"training failed: {exception}", file=sys.stderr)
        return 2
    print(f"ONNX model: {onnx_path}")
    print(f"Metadata:   {metadata_path}")
    print(json.dumps(metadata["metrics"], indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

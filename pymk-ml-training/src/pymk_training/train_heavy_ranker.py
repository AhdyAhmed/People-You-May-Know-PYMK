"""Day 25: seeded two-head CPU training, immutable ONNX and frozen-cohort comparisons."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import platform
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto
import onnxruntime as ort
import torch
import sklearn
from torch import nn
from sklearn.metrics import average_precision_score, log_loss, roc_auc_score
from sklearn.preprocessing import StandardScaler

from pymk_training.dataset import (FEATURE_NAMES, DatasetValidationError, load_datasets,
                                   load_multitarget_datasets, temporal_split)
from pymk_training.profile_embeddings import ProfileEmbeddingTransform, timestamp
from pymk_training.train_light_ranker import MODEL_VERSION_PATTERN, _atomic_write, _split_metadata

TARGETS = ("invite_sent", "invite_accepted")
OUTPUTS = ("invite_sent_probability", "invite_accepted_probability")


class HeavyRanker(nn.Module):
    def __init__(self, mean, scale, hidden_size):
        super().__init__()
        self.register_buffer("mean", torch.tensor(mean, dtype=torch.float32))
        self.register_buffer("scale", torch.tensor(scale, dtype=torch.float32))
        self.hidden = nn.Linear(len(FEATURE_NAMES), hidden_size)
        self.sent = nn.Linear(hidden_size, 1)
        self.accepted = nn.Linear(hidden_size, 1)

    def logits(self, features):
        hidden = torch.relu(self.hidden((features - self.mean) / self.scale))
        return self.sent(hidden), self.accepted(hidden)

    def forward(self, features):
        return tuple(torch.sigmoid(head) for head in self.logits(features))


def export_onnx(model: HeavyRanker, name: str) -> bytes:
    """Serialize the fixed network directly with standard ops; no exporter tracing dependency.

    Weights/buffers are copied from the trained PyTorch module. Runtime parity is
    mandatory before either artifact is published, including dynamic batch sizes.
    """
    tensors = {key: value.detach().cpu().numpy() for key, value in model.state_dict().items()}
    nodes = [helper.make_node("Sub", ["features", "mean"], ["centered"]),
             helper.make_node("Div", ["centered", "scale"], ["scaled"]),
             helper.make_node("Gemm", ["scaled", "hidden.weight", "hidden.bias"], ["hidden_linear"], transB=1),
             helper.make_node("Relu", ["hidden_linear"], ["hidden_relu"])]
    for head, output in zip(("sent", "accepted"), OUTPUTS):
        nodes += [helper.make_node("Gemm", ["hidden_relu", head + ".weight", head + ".bias"], [head + "_logit"], transB=1),
                  helper.make_node("Sigmoid", [head + "_logit"], [output])]
    graph = helper.make_graph(nodes, name,
                              [helper.make_tensor_value_info("features", TensorProto.FLOAT, ["batch", 6])],
                              [helper.make_tensor_value_info(output, TensorProto.FLOAT, ["batch", 1]) for output in OUTPUTS],
                              [numpy_helper.from_array(value, key) for key, value in tensors.items()])
    artifact = helper.make_model(graph, producer_name="pymk-heavy-ranker", opset_imports=[helper.make_opsetid("", 17)])
    artifact.ir_version = 8  # Compatible with the project's Java ONNX Runtime.
    onnx.checker.check_model(artifact)
    return artifact.SerializeToString()


def _probabilities(session, features):
    outputs = session.run(list(OUTPUTS), {"features": features.astype(np.float32, copy=False)})
    if any(value.shape != (len(features), 1) for value in outputs):
        raise DatasetValidationError("unexpected heavy ONNX output shape")
    values = np.concatenate(outputs, axis=1)
    if not np.isfinite(values).all() or np.any((values < 0) | (values > 1)):
        raise DatasetValidationError("invalid heavy probabilities")
    return values


def verify_parity(model, artifact, features):
    session = ort.InferenceSession(artifact, providers=["CPUExecutionProvider"])
    maximum = 0.0
    # Empty, singleton and multi-row inference must preserve named [N,1] outputs.
    batches = [features[:0], features[:1]] + [features[i:i + 256] for i in range(0, len(features), 256)]
    model.eval()
    with torch.no_grad():
        for batch in batches:
            expected = np.concatenate([v.numpy() for v in model(torch.from_numpy(batch))], axis=1)
            actual = _probabilities(session, batch)
            if not np.allclose(actual, expected, rtol=1e-5, atol=1e-6):
                raise DatasetValidationError("PyTorch/ONNX parity failed")
            if actual.size:
                maximum = max(maximum, float(np.max(np.abs(actual - expected))))
    return session, maximum


def _metrics(labels, scores, probabilities=True):
    both = len(np.unique(labels)) == 2
    result = {"rows": len(labels), "positives": int(labels.sum()), "positiveRate": float(labels.mean()),
              "rocAuc": float(roc_auc_score(labels, scores)) if both else None,
              "averagePrecision": float(average_precision_score(labels, scores)) if both else None}
    if probabilities:
        result["logLoss"] = float(log_loss(labels, scores, labels=[0, 1]))
    return result


def _frozen_l1(model_path, metadata_path, dataset, split, transform_hash):
    artifact = Path(model_path).read_bytes()
    metadata = json.loads(Path(metadata_path).read_text(encoding="utf-8"))
    compatibility = metadata.get("embeddingCompatibility", {})
    if (metadata.get("schemaVersion") != 1 or metadata.get("features") != list(FEATURE_NAMES)
            or metadata.get("positiveClass") != 1
            or metadata.get("onnx", {}).get("sha256") != hashlib.sha256(artifact).hexdigest()
            or compatibility.get("transformSha256") != transform_hash
            or compatibility.get("featureSchema") != "pair-v1"
            or metadata.get("split") != _split_metadata(split, dataset)):
        raise DatasetValidationError("frozen L1 contract, transform or chronological split differs")
    inputs = metadata.get("inputs", [])
    reference = load_datasets([Path(item["path"]) for item in inputs])
    if [item["sha256"] for item in inputs] != [item["sha256"] for item in reference.input_files]:
        raise DatasetValidationError("frozen L1 training inputs have changed")
    for field in ("member_ids", "candidate_ids", "features", "feature_as_of", "label_window_end"):
        if not np.array_equal(getattr(reference, field), getattr(dataset, field)):
            raise DatasetValidationError("frozen L1 and v2 evaluation cohorts differ")
    if not np.array_equal(reference.labels, dataset.labels[:, 1]):
        raise DatasetValidationError("frozen L1 acceptance labels differ")
    session = ort.InferenceSession(artifact, providers=["CPUExecutionProvider"])
    values = session.run(None, {"features": dataset.features})
    if len(values) != 2 or values[1].shape != (dataset.row_count, 2):
        raise DatasetValidationError("invalid frozen L1 output shape")
    probabilities = values[1][:, 1]
    if not np.isfinite(probabilities).all() or np.any((probabilities < 0) | (probabilities > 1)):
        raise DatasetValidationError("invalid frozen L1 probabilities")
    return probabilities, {"modelVersion": metadata["modelVersion"], "onnxSha256": hashlib.sha256(artifact).hexdigest(),
                           "metadataSha256": hashlib.sha256(Path(metadata_path).read_bytes()).hexdigest()}


def train_and_export(inputs, output_dir, model_version, embedding_transform, l1_model, l1_metadata,
                     epochs=200, hidden_size=16, learning_rate=0.01, seed=42):
    if not MODEL_VERSION_PATTERN.fullmatch(model_version):
        raise DatasetValidationError("invalid model version")
    if not 1 <= epochs <= 10000 or not 1 <= hidden_size <= 256 or not 0 < learning_rate <= 1 or not 0 <= seed < 2**32:
        raise DatasetValidationError("invalid heavy training parameters")
    output_dir = Path(output_dir).resolve()
    model_path, metadata_path = output_dir / f"{model_version}.onnx", output_dir / f"{model_version}.metadata.json"
    if model_path.exists() or metadata_path.exists():
        raise FileExistsError("model versions are immutable; choose a new model version")
    dataset = load_multitarget_datasets(inputs)
    split = temporal_split(dataset)
    transform_path = Path(embedding_transform)
    transform = ProfileEmbeddingTransform.load(transform_path)
    transform_hash = hashlib.sha256(transform_path.read_bytes()).hexdigest()
    for item in dataset.input_files:
        provenance = item["provenance"]
        if (provenance["transformSha256"] != transform_hash
                or provenance["embeddingVersion"] != transform.metadata["embeddingVersion"]
                or timestamp(provenance["fitCutoff"]) != timestamp(transform.metadata["fitCutoff"])):
            raise DatasetValidationError("dataset does not match frozen embedding transform")
    for index, target in enumerate(TARGETS):
        if set(dataset.labels[split.train, index]) != {0, 1}:
            raise DatasetValidationError(f"training partition needs both classes for {target}")
    l1_scores, l1_identity = _frozen_l1(l1_model, l1_metadata, dataset, split, transform_hash)
    scaler = StandardScaler().fit(dataset.features[split.train])
    previous_threads = torch.get_num_threads()
    deterministic = torch.are_deterministic_algorithms_enabled()
    warn_only = torch.is_deterministic_algorithms_warn_only_enabled()
    try:
        torch.set_num_threads(1)
        torch.use_deterministic_algorithms(True)
        with torch.random.fork_rng(devices=[]):
            torch.manual_seed(seed)
            model = HeavyRanker(scaler.mean_, scaler.scale_, hidden_size)
            optimizer = torch.optim.Adam(model.parameters(), lr=learning_rate)
            x = torch.from_numpy(dataset.features)
            y = torch.tensor(dataset.labels, dtype=torch.float32)
            criterion = nn.BCEWithLogitsLoss()
            best_loss, best_epoch, best_state = float("inf"), 0, None
            for epoch in range(1, epochs + 1):
                model.train()
                optimizer.zero_grad()
                loss = criterion(torch.cat(model.logits(x[split.train]), dim=1), y[split.train])
                if not torch.isfinite(loss):
                    raise DatasetValidationError("nonfinite training loss")
                loss.backward()
                optimizer.step()
                model.eval()
                with torch.no_grad():
                    validation_loss = float(criterion(torch.cat(model.logits(x[split.validation]), dim=1), y[split.validation]))
                if not np.isfinite(validation_loss):
                    raise DatasetValidationError("nonfinite validation loss")
                if validation_loss < best_loss:
                    best_loss, best_epoch, best_state = validation_loss, epoch, copy.deepcopy(model.state_dict())
            model.load_state_dict(best_state)
            model.eval()
            artifact = export_onnx(model, model_version)
            session, parity_error = verify_parity(model, artifact, dataset.features)
            predictions = _probabilities(session, dataset.features)
    finally:
        torch.set_num_threads(previous_threads)
        torch.use_deterministic_algorithms(deterministic, warn_only=warn_only)
    metrics, comparison = {}, {}
    for partition in ("train", "validation", "test"):
        indices = getattr(split, partition)
        metrics[partition] = {target: _metrics(dataset.labels[indices, i], predictions[indices, i]) for i, target in enumerate(TARGETS)}
        comparison[partition] = {"frozenL1Accepted": _metrics(dataset.labels[indices, 1], l1_scores[indices]),
                                 "mutualCountRanking": {target: _metrics(dataset.labels[indices, i], dataset.features[indices, 0], False) for i, target in enumerate(TARGETS)}}
    metadata = {
        "schemaVersion": 1, "modelVersion": model_version, "modelType": "standard-scaler+two-head-mlp",
        "createdAt": datetime.now(timezone.utc).isoformat(), "randomSeed": seed, "features": list(FEATURE_NAMES),
        "inputTensor": {"name": "features", "dtype": "float32", "shape": [None, 6]},
        "outputTensors": [{"name": output, "target": target, "dtype": "float32", "shape": [None, 1],
                           "semantics": "directional-unconditional-(featureAsOf,labelWindowEnd]"} for target, output in zip(TARGETS, OUTPUTS)],
        "inputs": list(dataset.input_files), "rowCount": dataset.row_count, "split": _split_metadata(split, dataset),
        "embeddingCompatibility": {"featureSchema": "pair-v1", "embeddingVersion": transform.metadata["embeddingVersion"],
                                   "transformSha256": transform_hash, "fitCutoff": transform.metadata["fitCutoff"]},
        "parameters": {"epochs": epochs, "selectedEpoch": best_epoch, "hiddenSize": hidden_size, "learningRate": learning_rate,
                       "optimizer": "Adam", "batchPolicy": "full-training-partition", "hiddenActivation": "ReLU",
                       "loss": "unweighted-mean-binary-cross-entropy-with-logits", "selection": "minimum-validation-loss",
                       "validationLoss": best_loss, "scalerMean": scaler.mean_.tolist(), "scalerScale": scaler.scale_.tolist()},
        "metrics": metrics, "comparison": {"cohortPolicy": "identical-snapshot-pairs-features-labels-and-splits", "l1": l1_identity,
                                          "partitions": comparison, "automaticPromotion": False},
        "runtime": {"python": platform.python_version(), "torch": torch.__version__, "numpy": np.__version__,
                    "onnx": onnx.__version__, "onnxRuntime": ort.__version__, "scikitLearn": sklearn.__version__, "device": "cpu", "threads": 1},
        "onnx": {"opset": 17, "irVersion": 8, "sha256": hashlib.sha256(artifact).hexdigest(), "maxParityAbsoluteError": parity_error},
    }
    payload = (json.dumps(metadata, indent=2, sort_keys=True, allow_nan=False) + "\n").encode()
    output_dir.mkdir(parents=True, exist_ok=True)
    _atomic_write(model_path, artifact)
    _atomic_write(metadata_path, payload)
    return model_path, metadata_path, metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", action="append", required=True, type=Path)
    for name in ("output-dir", "embedding-transform", "l1-model", "l1-metadata"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--model-version", required=True)
    parser.add_argument("--epochs", type=int, default=200)
    parser.add_argument("--hidden-size", type=int, default=16)
    parser.add_argument("--learning-rate", type=float, default=0.01)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()
    model, metadata, report = train_and_export(args.input, args.output_dir, args.model_version,
                                             args.embedding_transform, args.l1_model, args.l1_metadata,
                                             args.epochs, args.hidden_size, args.learning_rate, args.seed)
    print(f"ONNX model: {model}\nMetadata: {metadata}")
    print(json.dumps({"metrics": report["metrics"], "comparison": report["comparison"]}, indent=2))


if __name__ == "__main__":
    main()

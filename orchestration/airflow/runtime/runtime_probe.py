"""Actual isolated trainer + packaged production Java inference, no app database."""
import argparse
import base64
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    args_parser = argparse.ArgumentParser()
    args_parser.add_argument("--key", required=True)
    args_parser.add_argument("--source-sha256", required=True)
    args = args_parser.parse_args()
    if not all(re.fullmatch(r"[a-f0-9]{64}", item) for item in (args.key, args.source_sha256)):
        raise ValueError("Invalid workflow identity")
    source = Path("/opt/pymk/model.base64")
    if digest(source) != args.source_sha256:
        raise ValueError("Source fixture checksum does not match requested identity")
    # Heavy imports happen in the isolated job, never in Airflow's DAG parser.
    import numpy as np
    import onnxruntime as ort
    import torch
    import pymk_training.train_light_ranker
    import pymk_training.train_heavy_ranker
    import pymk_training.tracking
    jar = Path("/opt/pymk/pymk-batch.jar")
    root = Path("/opt/airflow/artifacts/runtime-contract")
    root.mkdir(parents=True, exist_ok=True)
    target = root / (args.key + ".json")
    with tempfile.TemporaryDirectory(dir=root) as temporary:
        stage = Path(temporary)
        model = stage / "model.onnx"
        model.write_bytes(base64.b64decode(source.read_text().strip()))
        metadata = {"schemaVersion": 1, "modelVersion": "day41-runtime-fixture", "positiveClass": 1,
            "features": ["mutual_connection_count", "same_company", "same_school", "same_geo", "embedding_cosine_sim", "profile_view_count_last_30d"],
            "inputTensor": {"name": "features", "dtype": "float32", "shape": [None, 6]}, "onnx": {"sha256": digest(model)}}
        (stage / "metadata.json").write_text(json.dumps(metadata))
        features = np.array([[0]*6, [1,1,0,0,.5,0], [2,0,1,0,.3,1], [3,0,0,1,-.1,2]], dtype=np.float32)
        session = ort.InferenceSession(model.read_bytes(), providers=["CPUExecutionProvider"])
        probabilities = [v for v in session.run(None, {"features": features}) if isinstance(v, np.ndarray) and v.shape == (4,2)]
        if len(probabilities) != 1: raise ValueError("Unexpected fixture outputs")
        (stage / "parity.json").write_text(json.dumps({"kind": "light", "features": features.tolist(), "expected": probabilities[0][:,1:2].tolist()}))
        subprocess.run(["java", "-Dloader.main=com.ahdyahmed.pymk.batch.RegistryModelVerifier", "-cp", str(jar),
            "org.springframework.boot.loader.launch.PropertiesLauncher", str(stage), str(stage / "java.json")], check=True, capture_output=True, timeout=90)
        record = {"schemaVersion": 1, "logicalKey": args.key, "sourceSha256": args.source_sha256,
            "javaJarSha256": digest(jar), "javaParity": json.loads((stage / "java.json").read_text()),
            "runtime": {p: importlib.metadata.version(p) for p in ("numpy", "onnxruntime", "torch", "scikit-learn", "mlflow-skinny")},
            "automaticPromotion": False}
        content = json.dumps(record, sort_keys=True).encode()
        completed = stage / "completed.json"
        completed.write_bytes(content)
        try:
            os.link(completed, target)
        except FileExistsError:
            if target.read_bytes() != content: raise ValueError("Logical output already identifies different runtime evidence")
    print(json.dumps({"path": str(target), "sha256": digest(target)}))


if __name__ == "__main__": main()

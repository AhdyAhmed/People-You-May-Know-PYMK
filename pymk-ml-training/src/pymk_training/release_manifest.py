"""Build an immutable Day 23 staging manifest from a verified frozen transform and vector CSV."""
import argparse
import csv
import hashlib
import json
from pathlib import Path

import numpy as np
from pymk_training.profile_embeddings import ProfileEmbeddingTransform, immutable_output, timestamp, validate_version


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--transform", type=Path, required=True)
    parser.add_argument("--vectors", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--graph-limit", type=int, default=2000)
    parser.add_argument("--heuristic-limit", type=int, default=1000)
    parser.add_argument("--embedding-limit", type=int, default=1000)
    parser.add_argument("--min-validation-auc", type=float, default=0.5)
    args = parser.parse_args(argv)
    validate_version(args.release_id)
    if args.release_id == "legacy" or min(args.graph_limit, args.heuristic_limit, args.embedding_limit) <= 0:
        raise ValueError("invalid release ID or candidate budgets")
    if not 0.5 <= args.min_validation_auc <= 1:
        raise ValueError("min validation AUC must be between 0.5 and 1")
    model = ProfileEmbeddingTransform.load(args.transform)
    ids = set()
    as_of = None
    with args.vectors.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != ["member_id", "embedding", "updated_at", "embedding_version", "fit_cutoff"]:
            raise ValueError("invalid vector CSV header")
        for row in reader:
            member = int(row["member_id"])
            instant = timestamp(row["updated_at"])
            values = np.asarray(json.loads(row["embedding"]), dtype=np.float32)
            norm = np.linalg.norm(values)
            if (member <= 0 or member in ids or None in row
                    or values.shape != (128,) or not np.isfinite(values).all()
                    or (norm != 0 and not np.isclose(norm, 1, atol=1e-5))
                    or row["embedding_version"] != model.metadata["embeddingVersion"]
                    or timestamp(row["fit_cutoff"]) != timestamp(model.metadata["fitCutoff"])
                    or instant < timestamp(model.metadata["fitCutoff"])
                    or (as_of is not None and as_of != instant)):
                raise ValueError("invalid, duplicate or incompatible vector row")
            ids.add(member)
            as_of = instant
    if not ids:
        raise ValueError("empty embedding generation")
    document = {"schemaVersion": 1, "releaseId": args.release_id, "featureAsOf": as_of.isoformat(),
                "transformPath": str(args.transform.resolve()), "vectorsPath": str(args.vectors.resolve()),
                "transformSha256": hashlib.sha256(args.transform.read_bytes()).hexdigest(),
                "vectorsSha256": hashlib.sha256(args.vectors.read_bytes()).hexdigest(),
                "candidateLimits": {"graph": args.graph_limit, "heuristic": args.heuristic_limit, "embedding": args.embedding_limit},
                "minValidationAuc": args.min_validation_auc}
    with immutable_output(args.output) as stream:
        json.dump(document, stream, sort_keys=True, indent=2, allow_nan=False)
    print(f"Staging manifest: {args.output} ({len(ids)} members)")


if __name__ == "__main__":
    main()

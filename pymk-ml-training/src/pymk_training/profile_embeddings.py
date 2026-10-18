"""Day 22: portable, frozen TF-IDF/SVD profile embeddings (no live activation)."""
from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import unicodedata
import tempfile
from contextlib import contextmanager

import numpy as np
import sklearn
from sklearn.decomposition import TruncatedSVD
from sklearn.feature_extraction.text import TfidfVectorizer

DIMENSIONS = 128
FIELDS = ("headline", "company", "school", "geo_region")
TOKEN_PATTERN = r"(?u)\b\w\w+\b"
VERSION_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}")


def validate_version(version):
    if (not isinstance(version, str) or version == "placeholder-v1"
            or VERSION_PATTERN.fullmatch(version) is None):
        raise ValueError("invalid embedding version")


def timestamp(value: str) -> datetime:
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("timestamps must have a timezone")
    return result.astimezone(timezone.utc)


def profile_text(row: dict) -> str:
    # Names/IDs are deliberately not semantic signals. Freeze this policy in metadata.
    return " ".join(unicodedata.normalize("NFKC", row.get(field) or "").casefold()
                    for field in FIELDS)


def read_profiles(path: Path, cutoff: datetime) -> list[dict]:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        required = {"member_id", "created_at", "profile_as_of", *FIELDS}
        if (not required.issubset(reader.fieldnames or [])
                or len(reader.fieldnames) != len(set(reader.fieldnames))):
            raise ValueError(f"profiles require columns {sorted(required)}")
        rows = list(reader)
    seen = set()
    eligible = []
    for row in rows:
        if None in row or any(value is None for value in row.values()):
            raise ValueError("profile CSV row does not match its header")
        member_id = int(row["member_id"])
        if member_id <= 0 or member_id in seen:
            raise ValueError("member IDs must be positive and unique")
        seen.add(member_id)
        created = timestamp(row["created_at"])
        observed = timestamp(row["profile_as_of"])
        if observed < created:
            raise ValueError("profile_as_of precedes member creation")
        if created > cutoff:
            continue
        if observed > cutoff:
            raise ValueError("historical profile snapshot required: profile_as_of exceeds cutoff")
        eligible.append(row)
    if not eligible:
        raise ValueError("no eligible profiles at cutoff")
    return sorted(eligible, key=lambda row: int(row["member_id"]))


def canonical(value: dict) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


@contextmanager
def immutable_output(path: Path, *, newline=None):
    """Publish a completed local file without ever overwriting an existing version."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline=newline,
                                         dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            yield stream
            stream.flush()
            os.fsync(stream.fileno())
        # Same-directory hard link is atomic and refuses an existing destination.
        os.link(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


class ProfileEmbeddingTransform:
    def __init__(self, metadata: dict, vocabulary: dict, idf: list, components: list):
        try:
            validate_version(metadata["embeddingVersion"])
            timestamp(metadata["fitCutoff"])
            dimensions = metadata["effectiveDimensions"]
            if (type(dimensions) is not int or not 0 <= dimensions <= DIMENSIONS
                    or type(metadata["fitMemberCount"]) is not int or metadata["fitMemberCount"] < 1
                    or metadata["blankPolicy"] != "zero-vector"
                    or metadata["smallCorpusPolicy"] != "direct-tfidf-or-bounded-svd"
                    or not re.fullmatch(r"[0-9a-f]{64}", metadata["fitProfilesSha256"])
                    or not isinstance(metadata["sklearnVersion"], str) or not metadata["sklearnVersion"]
                    or metadata["seed"] != 42
                    or not isinstance(vocabulary, dict)
                    or any(not isinstance(token, str) or not token or type(index) is not int
                           for token, index in vocabulary.items())):
                raise ValueError("invalid embedding transform metadata")
        except (KeyError, TypeError, AttributeError) as error:
            raise ValueError("invalid embedding transform metadata") from error
        self.metadata = metadata
        self.vocabulary = vocabulary
        self.idf = np.asarray(idf, dtype=np.float64)
        self.components = np.asarray(components, dtype=np.float64)
        # JSON's [] has no second dimension. Only the explicitly empty transform
        # may reshape; flattening an arbitrary matrix would silently change semantics.
        if dimensions == 0 and not vocabulary and self.components.shape == (0,):
            self.components = self.components.reshape(0, 0)
        if (metadata["dimensions"] != DIMENSIONS or metadata["schemaVersion"] != 1
                or metadata["textPolicy"] != "nfkc-casefold-headline-company-school-geo-v1"
                or sorted(vocabulary.values()) != list(range(len(vocabulary)))
                or self.idf.shape != (len(vocabulary),)
                or self.components.shape != (dimensions, len(vocabulary))
                or not np.isfinite(self.idf).all() or not np.isfinite(self.components).all()
                or np.any(self.idf <= 0)
                or (bool(vocabulary) != bool(metadata["effectiveDimensions"]))
                or not 0 <= metadata["effectiveDimensions"] <= DIMENSIONS):
            raise ValueError("invalid embedding transform contract")

    @classmethod
    def fit(cls, rows: list[dict], cutoff: datetime, version: str):
        validate_version(version)
        # Sort makes SVD fitting invariant to CSV row order; callers cannot bypass cutoff.
        rows = sorted(rows, key=lambda row: int(row["member_id"]))
        if (not rows or any(int(row["member_id"]) <= 0 for row in rows)
                or len({int(row["member_id"]) for row in rows}) != len(rows)):
            raise ValueError("fit requires nonempty, positive, unique member profiles")
        for row in rows:
            if (timestamp(row["created_at"]) > cutoff
                    or timestamp(row["profile_as_of"]) > cutoff
                    or timestamp(row["profile_as_of"]) < timestamp(row["created_at"])):
                raise ValueError("fit profiles must be available at cutoff")
        texts = [profile_text(row) for row in rows]
        vocabulary, idf, components = {}, [], []
        # An all-blank/tokenless corpus has an explicitly neutral frozen transform.
        if any(re.search(TOKEN_PATTERN, text) for text in texts):
            vectorizer = TfidfVectorizer(lowercase=False, token_pattern=TOKEN_PATTERN,
                                         max_features=20000, dtype=np.float64)
            matrix = vectorizer.fit_transform(texts)
            vocabulary = {token: int(index) for token, index in vectorizer.vocabulary_.items()}
            idf = vectorizer.idf_.tolist()
            means = np.asarray(matrix.mean(axis=0)).ravel()
            variances = np.asarray(matrix.power(2).mean(axis=0)).ravel() - means ** 2
            if np.max(variances) <= 1e-15 or matrix.shape[1] == 1:
                # Rank-one corpus: its normalized TF-IDF direction, no unstable SVD fit.
                first_nonzero = int(np.flatnonzero(matrix.getnnz(axis=1))[0])
                components = matrix.getrow(first_nonzero).toarray().tolist()
            else:
                count = min(DIMENSIONS, matrix.shape[0], matrix.shape[1] - 1)
                svd = TruncatedSVD(n_components=count, random_state=42, n_iter=7)
                svd.fit(matrix)
                components = svd.components_.tolist()
        metadata = {
            "schemaVersion": 1, "embeddingVersion": version, "dimensions": DIMENSIONS,
            "effectiveDimensions": len(components), "fitCutoff": cutoff.isoformat(),
            "fitMemberCount": len(rows), "seed": 42, "sklearnVersion": sklearn.__version__,
            "textPolicy": "nfkc-casefold-headline-company-school-geo-v1",
            "blankPolicy": "zero-vector", "smallCorpusPolicy": "direct-tfidf-or-bounded-svd",
            "fitProfilesSha256": hashlib.sha256(canonical({"profiles": rows})).hexdigest(),
        }
        return cls(metadata, vocabulary, idf, components)

    def transform(self, rows: list[dict]) -> np.ndarray:
        vectors = np.zeros((len(rows), DIMENSIONS), dtype=np.float32)
        if not rows or not self.vocabulary:
            return vectors
        vectorizer = TfidfVectorizer(vocabulary=self.vocabulary, lowercase=False,
                                     token_pattern=TOKEN_PATTERN, dtype=np.float64)
        vectorizer.idf_ = self.idf
        reduced = vectorizer.transform([profile_text(row) for row in rows]) @ self.components.T
        norms = np.linalg.norm(reduced, axis=1, keepdims=True)
        reduced = np.divide(reduced, norms, out=np.zeros_like(reduced), where=norms > 1e-12)
        vectors[:, :reduced.shape[1]] = reduced.astype(np.float32)
        if not np.isfinite(vectors).all():
            raise ValueError("nonfinite embedding output")
        return vectors

    def save(self, path: Path):
        payload = {"metadata": self.metadata, "vocabulary": self.vocabulary,
                   "idf": self.idf.tolist(), "components": self.components.tolist()}
        document = {"sha256": hashlib.sha256(canonical(payload)).hexdigest(), "payload": payload}
        with immutable_output(path) as stream:
            json.dump(document, stream, sort_keys=True, allow_nan=False)

    @classmethod
    def load(cls, path: Path):
        document = json.loads(path.read_text(encoding="utf-8"))
        try:
            payload = document["payload"]
            if hashlib.sha256(canonical(payload)).hexdigest() != document["sha256"]:
                raise ValueError("embedding transform checksum mismatch")
            return cls(**payload)
        except (KeyError, TypeError, AttributeError) as error:
            raise ValueError("invalid embedding transform document") from error


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--as-of", required=True)
    parser.add_argument("--fit", action="store_true")
    parser.add_argument("--embedding-version")
    args = parser.parse_args(argv)
    as_of = timestamp(args.as_of)
    rows = read_profiles(args.input, as_of)
    if args.fit:
        if not args.embedding_version:
            parser.error("--fit requires --embedding-version")
        model = ProfileEmbeddingTransform.fit(rows, as_of, args.embedding_version)
    else:
        model = ProfileEmbeddingTransform.load(args.artifact)
        if args.embedding_version and args.embedding_version != model.metadata["embeddingVersion"]:
            raise ValueError("requested embedding version does not match the frozen artifact")
        if timestamp(model.metadata["fitCutoff"]) > as_of:
            raise ValueError("cannot use a future-fitted transform for historical vectors")
    vectors = model.transform(rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists() or (args.fit and args.artifact.exists()):
        raise FileExistsError("embedding outputs are immutable; choose new paths/version")
    if args.artifact.resolve() == args.output.resolve():
        raise ValueError("artifact and vector output must be different paths")
    if args.fit:
        model.save(args.artifact)
    with immutable_output(args.output, newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["member_id", "embedding", "updated_at", "embedding_version", "fit_cutoff"])
        for row, vector in zip(rows, vectors):
            writer.writerow([row["member_id"], json.dumps(vector.tolist(), allow_nan=False),
                             as_of.isoformat(), model.metadata["embeddingVersion"],
                             model.metadata["fitCutoff"]])
    print(f"Exported {len(rows)} finite {DIMENSIONS}-dimensional vectors to {args.output}")


if __name__ == "__main__":
    main()

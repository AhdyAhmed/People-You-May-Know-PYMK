import csv
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from pymk_training.profile_embeddings import main as embeddings_main, ProfileEmbeddingTransform, timestamp
from pymk_training.release_manifest import main as manifest_main
from pymk_training.train_light_ranker import train_and_export
from pymk_training.dataset import DatasetValidationError
from test_light_ranker_training import _write_snapshots


class ReleaseManifestTest(unittest.TestCase):
    def test_verified_manifest_binds_vectors_transform_and_limits(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = Path(__file__).parent / "fixtures/profiles.csv"
            transform, vectors, manifest = root / "transform.json", root / "vectors.csv", root / "release.json"
            embeddings_main(["--input", str(fixture), "--fit", "--embedding-version", "profile-release-v1",
                             "--as-of", "2026-01-03T00:00:00Z", "--artifact", str(transform), "--output", str(vectors)])
            args = ["--release-id", "release-v1", "--transform", str(transform), "--vectors", str(vectors), "--output", str(manifest)]
            manifest_main(args)
            document = json.loads(manifest.read_text())
            self.assertEqual(document["vectorsSha256"], hashlib.sha256(vectors.read_bytes()).hexdigest())
            self.assertEqual(document["transformSha256"], hashlib.sha256(transform.read_bytes()).hexdigest())
            self.assertEqual(document["candidateLimits"]["graph"], 2000)
            with self.assertRaises(FileExistsError):
                manifest_main(args)
            with vectors.open(newline="") as stream:
                rows = list(csv.DictReader(stream))
            rows.append(rows[0])
            with vectors.open("w", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=rows[0].keys())
                writer.writeheader()
                writer.writerows(rows)
            with self.assertRaisesRegex(ValueError, "duplicate"):
                manifest_main(args[:-1] + [str(root / "invalid.json")])

    def test_training_requires_matching_provenance_and_records_compatibility(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inputs = _write_snapshots(root / "data", 6)
            row = {"member_id":"1", "headline":"java backend", "company":"Acme", "school":"", "geo_region":"",
                   "created_at":"2025-12-01T00:00:00Z", "profile_as_of":"2025-12-02T00:00:00Z"}
            transform = root / "transform.json"
            ProfileEmbeddingTransform.fit([row], timestamp("2025-12-03T00:00:00Z"), "profile-release-v1").save(transform)
            with self.assertRaisesRegex(DatasetValidationError, "provenance sidecar"):
                train_and_export(inputs, root / "models", "release-v1", embedding_transform=transform)
            for index, path in enumerate(inputs):
                sidecar = {"schemaVersion":1, "releaseId":f"snapshot-{index}",
                           "csvSha256":hashlib.sha256(path.read_bytes()).hexdigest(),
                           "transformSha256":hashlib.sha256(transform.read_bytes()).hexdigest(),
                           "embeddingVersion":"profile-release-v1"}
                Path(str(path) + ".provenance.json").write_text(json.dumps(sidecar))
            _, _, metadata = train_and_export(inputs, root / "models", "release-v1", embedding_transform=transform)
            self.assertEqual(metadata["embeddingCompatibility"]["transformSha256"], hashlib.sha256(transform.read_bytes()).hexdigest())
            self.assertEqual(len(metadata["embeddingCompatibility"]["trainingReleases"]), 6)
            inputs[0].write_text(inputs[0].read_text().replace("0.3", "0.2"))
            # Change an attested hash explicitly, even if decimal formatting differed.
            sidecar_path = Path(str(inputs[0]) + ".provenance.json")
            sidecar = json.loads(sidecar_path.read_text())
            sidecar["csvSha256"] = "0" * 64
            sidecar_path.write_text(json.dumps(sidecar))
            with self.assertRaisesRegex(DatasetValidationError, "provenance does not match"):
                train_and_export(inputs, root / "models", "release-v2", embedding_transform=transform)


if __name__ == "__main__":
    unittest.main()

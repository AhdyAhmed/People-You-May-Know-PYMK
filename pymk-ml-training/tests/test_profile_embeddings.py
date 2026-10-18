import csv
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import numpy as np
from pymk_training.profile_embeddings import (
    DIMENSIONS, ProfileEmbeddingTransform, canonical, immutable_output, main, read_profiles, timestamp,
)

FIXTURE = Path(__file__).parent / "fixtures" / "profiles.csv"
CUTOFF = timestamp("2026-01-03T00:00:00Z")


class ProfileEmbeddingTest(unittest.TestCase):
    def setUp(self):
        self.rows = read_profiles(FIXTURE, CUTOFF)

    def fit(self, rows=None):
        return ProfileEmbeddingTransform.fit(self.rows if rows is None else rows, CUTOFF, "profile-svd-v1")

    def test_dimensions_normalization_neighbors_and_neutral_blank(self):
        vectors = self.fit().transform(self.rows)
        self.assertEqual(vectors.shape, (4, DIMENSIONS))
        self.assertTrue(np.isfinite(vectors).all())
        np.testing.assert_allclose(np.linalg.norm(vectors[:3], axis=1), 1, atol=1e-6)
        np.testing.assert_array_equal(vectors[3], 0)
        self.assertGreater(float(vectors[0] @ vectors[1]), 0.99)
        self.assertGreater(float(vectors[0] @ vectors[1]), float(vectors[0] @ vectors[2]) + 0.5)

    def test_deterministic_fit_row_order_and_frozen_reload(self):
        model = self.fit()
        expected = model.transform(self.rows)
        np.testing.assert_array_equal(expected, self.fit(list(reversed(self.rows))).transform(self.rows))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "transform.json"
            model.save(path)
            loaded = ProfileEmbeddingTransform.load(path)
            np.testing.assert_array_equal(expected, loaded.transform(self.rows))
            self.assertEqual(loaded.metadata["fitCutoff"], CUTOFF.isoformat())
            with self.assertRaises(FileExistsError):
                model.save(path)
            document = json.loads(path.read_text())
            document["payload"]["idf"][0] += 1
            path.write_text(json.dumps(document))
            with self.assertRaisesRegex(ValueError, "checksum"):
                ProfileEmbeddingTransform.load(path)

    def test_blank_tokenless_single_document_and_single_term_corpora(self):
        blank = [dict(self.rows[3])]
        for text in ("", "! a", "java", "java backend distributed"):
            with self.subTest(text=text):
                row = {**blank[0], "headline": text}
                vectors = self.fit([row]).transform([row])
                self.assertTrue(np.isfinite(vectors).all())
                self.assertEqual(vectors.shape, (1, DIMENSIONS))
        rows = [{**row, **{field: "" for field in ("company", "school", "geo_region")},
                 "headline": "java"} for row in self.rows]
        np.testing.assert_allclose(self.fit(rows).transform(rows)[:, 0], 1)
        # A blank first member must not become the rank-one projection direction.
        rows[0]["headline"] = ""
        vectors = self.fit(rows).transform(rows)
        np.testing.assert_array_equal(vectors[0], 0)
        np.testing.assert_allclose(vectors[1:, 0], 1)

    def test_failed_publication_leaves_no_partial_artifact_or_tempfile(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "artifact.json"
            with self.assertRaisesRegex(RuntimeError, "interrupted"):
                with immutable_output(path) as stream:
                    stream.write("partial")
                    raise RuntimeError("interrupted")
            self.assertEqual(list(Path(directory).iterdir()), [])
            with immutable_output(path) as stream:
                stream.write("complete")
            with self.assertRaises(FileExistsError):
                with immutable_output(path) as stream:
                    stream.write("overwrite")
            self.assertEqual(path.read_text(), "complete")
            self.assertEqual(list(Path(directory).iterdir()), [path])

    def test_unicode_normalization_ignores_names_and_ids(self):
        model = self.fit()
        changed = {**self.rows[0], "member_id": "99", "full_name": "Unrelated identity",
                   "headline": "ＪＡＶＡ BACKEND ENGINEER DISTRIBUTED SYSTEMS"}
        np.testing.assert_array_equal(model.transform([self.rows[0]]), model.transform([changed]))

    def test_unknown_terms_neutral_without_refitting(self):
        model = self.fit()
        unknown = {**self.rows[3], "headline": "unseenword"}
        np.testing.assert_array_equal(model.transform([unknown]), 0)
        self.assertNotIn("unseenword", model.vocabulary)

    def test_equivalent_bags_of_words_use_stable_rank_one_transform(self):
        # Same normalized term frequencies despite word order / punctuation.
        rows = [{**row, "headline": text, "company": "", "school": "", "geo_region": ""}
                for row, text in zip(self.rows, ("java backend", "backend java", "java, backend", "java backend java backend"))]
        model = self.fit(rows)
        self.assertEqual(model.metadata["effectiveDimensions"], 1)
        np.testing.assert_allclose(model.transform(rows)[:, 0], 1)

    def test_artifact_schema_checked_even_when_checksum_is_valid(self):
        with tempfile.TemporaryDirectory() as directory:
            original = Path(directory) / "original.json"
            self.fit().save(original)
            baseline = json.loads(original.read_text())
            mutations = [
                lambda p: p["metadata"].update(embeddingVersion="placeholder-v1"),
                lambda p: p["metadata"].update(fitCutoff="2026-01-03"),
                lambda p: p["metadata"].update(blankPolicy="random"),
                lambda p: p["metadata"].update(fitMemberCount=0),
                lambda p: p["metadata"].pop("fitProfilesSha256"),
                lambda p: p.update(components=np.asarray(p["components"]).ravel().tolist()),
                lambda p: p["vocabulary"].update(java=True),
            ]
            for index, mutate in enumerate(mutations):
                with self.subTest(index=index):
                    document = copy.deepcopy(baseline)
                    mutate(document["payload"])
                    document["sha256"] = hashlib.sha256(canonical(document["payload"])).hexdigest()
                    path = Path(directory) / f"invalid-{index}.json"
                    path.write_text(json.dumps(document))
                    with self.assertRaises(ValueError):
                        ProfileEmbeddingTransform.load(path)

    def test_direct_fit_rejects_nonpositive_member_ids(self):
        with self.assertRaisesRegex(ValueError, "positive"):
            self.fit([{**self.rows[0], "member_id": "0"}])

    def test_shared_java_pgvector_fixture_matches_python_export(self):
        path = Path(__file__).resolve().parents[2] / "pymk-domain/src/test/resources/embeddings/profile-svd-v1.tsv"
        expected = [json.loads(line.split("\t")[1]) for line in path.read_text().splitlines()]
        np.testing.assert_allclose(self.fit().transform(self.rows), expected, atol=1e-6)

    def test_rejects_future_fit_data_and_naive_times(self):
        for field in ("created_at", "profile_as_of"):
            with self.assertRaises(ValueError):
                self.fit([{**self.rows[0], field: "2026-02-01T00:00:00Z"}])
        with self.assertRaises(ValueError):
            timestamp("2026-01-01T00:00:00")

    def test_cli_fit_transform_and_future_artifact_rejection(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "model.json"
            output = Path(directory) / "vectors.csv"
            args = ["--input", str(FIXTURE), "--artifact", str(artifact),
                    "--output", str(output), "--as-of", CUTOFF.isoformat()]
            main([*args, "--fit", "--embedding-version", "profile-svd-v1"])
            with output.open(newline="") as stream:
                exported = list(csv.DictReader(stream))
            self.assertEqual(len(exported), 4)
            self.assertEqual(len(json.loads(exported[0]["embedding"])), DIMENSIONS)
            self.assertEqual(exported[0]["embedding_version"], "profile-svd-v1")
            main([*args[:4], "--output", str(Path(directory) / "next.csv"),
                  "--as-of", "2026-01-04T00:00:00Z"])
            with self.assertRaisesRegex(ValueError, "future-fitted"):
                main([*args[:4], "--output", str(Path(directory) / "historical.csv"),
                      "--as-of", "2026-01-02T00:00:00Z"])
            with self.assertRaises(FileExistsError):
                main([*args, "--fit", "--embedding-version", "profile-svd-v1"])
            with self.assertRaisesRegex(ValueError, "does not match"):
                main([*args[:4], "--output", str(Path(directory) / "mismatch.csv"),
                      "--as-of", CUTOFF.isoformat(), "--embedding-version", "other-v1"])

    def test_csv_temporal_and_duplicate_guards(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "profiles.csv"
            def write(rows):
                with path.open("w", newline="") as stream:
                    writer = csv.DictWriter(stream, fieldnames=self.rows[0].keys())
                    writer.writeheader()
                    writer.writerows(rows)
            write([self.rows[0], self.rows[0]])
            with self.assertRaisesRegex(ValueError, "unique"):
                read_profiles(path, CUTOFF)
            write([{**self.rows[0], "profile_as_of": "2026-02-01T00:00:00Z"}])
            with self.assertRaisesRegex(ValueError, "historical profile"):
                read_profiles(path, CUTOFF)
            write([self.rows[0], {**self.rows[1], "created_at": "2026-02-01T00:00:00Z",
                                 "profile_as_of": "2026-02-02T00:00:00Z"}])
            self.assertEqual(len(read_profiles(path, CUTOFF)), 1)
            path.write_text(FIXTURE.read_text().replace("headline,", "headline,headline,", 1))
            with self.assertRaises(ValueError):
                read_profiles(path, CUTOFF)
            path.write_text(FIXTURE.read_text().replace("1,Java", "1,extra,Java", 1))
            with self.assertRaisesRegex(ValueError, "header"):
                read_profiles(path, CUTOFF)


if __name__ == "__main__":
    unittest.main()

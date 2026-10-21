import csv
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import numpy as np
import onnxruntime as ort
import torch

import test_dataset_v2
from pymk_training.dataset import CSV_COLUMNS, DatasetValidationError, load_multitarget_datasets
from pymk_training.train_light_ranker import train_and_export as train_l1
from pymk_training.train_heavy_ranker import OUTPUTS, train_and_export, _probabilities


class HeavyRankerTrainingTest(unittest.TestCase):
    def setUp(self):
        self.fixture = test_dataset_v2.MultiTargetDatasetTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        self.transform = self.root / 'transform.json'
        source = Path(__file__).resolve().parents[2] / 'pymk-batch/src/test/resources/day23/transform.json'
        self.transform.write_bytes(source.read_bytes())
        transform_metadata = json.loads(self.transform.read_text())['payload']['metadata']
        self.hash = hashlib.sha256(self.transform.read_bytes()).hexdigest()
        self.paths = [self.fixture.snapshot(), self.fixture.snapshot(10, 17), self.fixture.snapshot(17, 24)]
        for path in self.paths:
            self.fixture.change_metadata(path, transformSha256=self.hash,
                                         embeddingVersion=transform_metadata['embeddingVersion'],
                                         fitCutoff=transform_metadata['fitCutoff'])
        self.freeze_l1('l1')

    def freeze_l1(self, name):
        paths = []
        for i, source in enumerate(self.paths):
            with source.open(newline='', encoding='utf-8') as stream:
                rows = list(csv.reader(stream))[1:]
            path = self.root / f'{name}-{i}.csv'
            with path.open('w', newline='', encoding='utf-8') as stream:
                writer = csv.writer(stream)
                writer.writerow(CSV_COLUMNS)
                writer.writerows([row[1:-2] + [row[-1]] for row in rows])
            provenance = json.loads(self.fixture.sidecar(source).read_text())
            provenance.update(schemaVersion=1, csvSha256=hashlib.sha256(path.read_bytes()).hexdigest())
            self.fixture.sidecar(path).write_text(json.dumps(provenance))
            paths.append(path)
        self.l1, self.l1_metadata, _ = train_l1(paths, self.root / name, name, embedding_transform=self.transform)

    def train(self, version='heavy', **kwargs):
        return train_and_export(self.paths, self.root / 'models', version, self.transform,
                                self.l1, self.l1_metadata, epochs=kwargs.pop('epochs', 12), **kwargs)

    def test_actual_onnx_named_outputs_preprocessing_and_comparison(self):
        rng, threads = torch.get_rng_state().clone(), torch.get_num_threads()
        path, metadata_path, metadata = self.train()
        self.assertTrue(torch.equal(torch.get_rng_state(), rng))
        self.assertEqual(torch.get_num_threads(), threads)
        self.assertEqual(json.loads(metadata_path.read_text()), metadata)
        self.assertEqual(metadata['onnx']['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
        self.assertLessEqual(metadata['onnx']['maxParityAbsoluteError'], 1e-6)
        self.assertEqual([v['name'] for v in metadata['outputTensors']], list(OUTPUTS))
        self.assertEqual(metadata['embeddingCompatibility']['transformSha256'], self.hash)
        self.assertFalse(metadata['comparison']['automaticPromotion'])
        data = load_multitarget_datasets(self.paths)
        np.testing.assert_allclose(metadata['parameters']['scalerMean'], data.features[:3].mean(axis=0))
        runtime = ort.InferenceSession(path.read_bytes(), providers=['CPUExecutionProvider'])
        for size in (0, 1, 7, 9):
            predictions = _probabilities(runtime, data.features[:size])
            self.assertEqual(predictions.shape, (size, 2))
            self.assertTrue(np.isfinite(predictions).all())
        self.assertEqual(metadata['metrics']['test']['invite_sent']['rows'], 3)
        self.assertIn('rocAuc', metadata['comparison']['partitions']['test']['frozenL1Accepted'])
        self.assertNotIn('logLoss', metadata['comparison']['partitions']['test']['mutualCountRanking']['invite_sent'])

    def test_reproducible_weights_and_test_features_do_not_select_checkpoint(self):
        first, _, report = self.train('one')
        second, _, repeated = self.train('two')
        probe = load_multitarget_datasets(self.paths).features
        def predict(path):
            return _probabilities(ort.InferenceSession(path.read_bytes(), providers=['CPUExecutionProvider']), probe)
        np.testing.assert_array_equal(predict(first), predict(second))
        self.assertEqual(report['parameters'], repeated['parameters'])
        # Only held-out features/labels change; refreeze the comparison cohort.
        self.fixture.change_rows(self.paths[2], lambda rows: [row.__setitem__(3, '100') for row in rows[1:]])
        self.freeze_l1('l1-test-change')
        third, _, changed = self.train('three')
        np.testing.assert_array_equal(predict(first), predict(third))
        self.assertEqual(report['parameters'], changed['parameters'])

    def test_immutable_publication_and_parity_failure_publish_nothing(self):
        path, metadata, _ = self.train()
        original = path.read_bytes(), metadata.read_bytes()
        with self.assertRaises(FileExistsError):
            self.train()
        self.assertEqual(original, (path.read_bytes(), metadata.read_bytes()))
        with patch('pymk_training.train_heavy_ranker.verify_parity', side_effect=DatasetValidationError('parity failure')):
            with self.assertRaisesRegex(DatasetValidationError, 'parity failure'):
                self.train('bad-parity')
        self.assertFalse((path.parent / 'bad-parity.onnx').exists())
        self.assertFalse((path.parent / 'bad-parity.metadata.json').exists())

    def test_rejects_changed_l1_cohorts_artifacts_and_transforms(self):
        original = self.l1.read_bytes()
        self.l1.write_bytes(original + b'corrupt')
        with self.assertRaisesRegex(DatasetValidationError, 'frozen L1 contract'):
            self.train()
        self.l1.write_bytes(original)
        self.fixture.change_rows(self.paths[2], lambda rows: rows[1].__setitem__(3, '10'))
        with self.assertRaisesRegex(DatasetValidationError, 'cohorts differ'):
            self.train()
        self.fixture.change_metadata(self.paths[0], transformSha256='f' * 64)
        with self.assertRaisesRegex(DatasetValidationError, 'incompatible embedding'):
            self.train()

    def test_parameter_bounds_and_single_class_training_fail_before_publication(self):
        for params in ({'epochs': 0}, {'learning_rate': float('nan')}, {'hidden_size': 0}, {'seed': -1}):
            with self.subTest(params=params), self.assertRaisesRegex(DatasetValidationError, 'parameters'):
                self.train(**params)
        self.fixture.change_rows(self.paths[0], lambda rows: [row.__setitem__(-2, '0') for row in rows[1:]])
        with self.assertRaisesRegex(DatasetValidationError, 'both classes for invite_sent'):
            self.train()
        self.assertFalse((self.root / 'models').exists())

    def test_single_class_held_out_metrics_are_unavailable_not_fake(self):
        for path in self.paths[1:]:
            self.fixture.change_rows(path, lambda rows: [row.__setitem__(-2, '0') for row in rows[1:]])
        _, _, metadata = self.train()
        self.assertIsNone(metadata['metrics']['test']['invite_sent']['rocAuc'])
        self.assertIsNone(metadata['metrics']['validation']['invite_sent']['averagePrecision'])
        self.assertTrue(np.isfinite(metadata['metrics']['test']['invite_sent']['logLoss']))


if __name__ == '__main__':
    unittest.main()

import base64
import json
from pathlib import Path
import tempfile
import unittest

import numpy as np
from pymk_training.heavy_serving_fixture import build_fixture


class HeavyServingFixtureTest(unittest.TestCase):
    def test_committed_java_fixture_reproduces_actual_python_predictions(self):
        path = Path(__file__).resolve().parents[2] / 'pymk-heavy-ranker/src/test/resources/heavy-serving-fixture.json'
        fixture = json.loads(path.read_text(encoding='utf-8'))
        with tempfile.TemporaryDirectory() as directory:
            model = Path(directory) / 'model.onnx'
            metadata = Path(directory) / 'metadata.json'
            model.write_bytes(base64.b64decode(fixture['modelBase64']))
            metadata.write_text(json.dumps(fixture['metadata']), encoding='utf-8')
            rebuilt = build_fixture(model, metadata)
            np.testing.assert_allclose(rebuilt['expected'], fixture['expected'], rtol=0, atol=1e-6)
            self.assertEqual(rebuilt['features'], fixture['features'])
            self.assertEqual(set(rebuilt['invalidModels']), {'wrongNames', 'fixedBatch', 'wrongDtype', 'nanProbability'})
            broken = fixture['metadata'].copy()
            broken['onnx'] = {'sha256': '0' * 64}
            metadata.write_text(json.dumps(broken), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                build_fixture(model, metadata)

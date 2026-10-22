"""Print a Java/Python parity fixture from an actual Day 25 ONNX artifact (no retraining)."""
import argparse
import base64
import copy
import hashlib
import json
from pathlib import Path

import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto
import onnxruntime as ort


def build_fixture(model_path, metadata_path):
    artifact = Path(model_path).read_bytes()
    metadata_bytes = Path(metadata_path).read_bytes()
    metadata = json.loads(metadata_bytes)
    if metadata['onnx']['sha256'] != hashlib.sha256(artifact).hexdigest():
        raise ValueError('model checksum mismatch')
    fields = ('schemaVersion', 'modelVersion', 'modelType', 'features', 'inputTensor',
              'outputTensors', 'embeddingCompatibility', 'onnx', 'split')
    features = np.asarray([[0, 0, 0, 0, 0, 0], [5, 1, 1, 1, .9, 3],
                           [0, 0, 0, 0, -.5, 0], [7, 1, 0, 1, .25, 4]], dtype=np.float32)
    outputs = [head['name'] for head in metadata['outputTensors']]
    runtime = ort.InferenceSession(artifact, providers=['CPUExecutionProvider'])
    scores = runtime.run(outputs, {'features': features})
    variants = {}
    original = onnx.load_model_from_string(artifact)
    changed = copy.deepcopy(original)
    for node in changed.graph.node:
        for i, value in enumerate(node.output):
            if value == outputs[1]:
                node.output[i] = 'wrong_output'
    changed.graph.output[1].name = 'wrong_output'
    variants['wrongNames'] = changed
    changed = copy.deepcopy(original)
    changed.graph.input[0].type.tensor_type.shape.dim[0].dim_value = 1
    variants['fixedBatch'] = changed
    changed = copy.deepcopy(original)
    for node in changed.graph.node:
        for i, value in enumerate(node.output):
            if value == outputs[1]:
                node.output[i] = 'accepted_raw'
    changed.graph.node.append(helper.make_node('Cast', ['accepted_raw'], [outputs[1]], to=TensorProto.DOUBLE))
    changed.graph.output[1].type.tensor_type.elem_type = TensorProto.DOUBLE
    variants['wrongDtype'] = changed
    changed = copy.deepcopy(original)
    for node in changed.graph.node:
        for i, value in enumerate(node.output):
            if value == outputs[1]:
                node.output[i] = 'accepted_raw'
    changed.graph.initializer.append(numpy_helper.from_array(np.asarray([np.nan], dtype=np.float32), 'bad_multiplier'))
    changed.graph.node.append(helper.make_node('Mul', ['accepted_raw', 'bad_multiplier'], [outputs[1]]))
    variants['nanProbability'] = changed
    for graph in variants.values():
        onnx.checker.check_model(graph)
    return {'sourceMetadataSha256': hashlib.sha256(metadata_bytes).hexdigest(),
            'modelBase64': base64.b64encode(artifact).decode(),
            'metadata': {key: metadata[key] for key in fields},
            'features': features.tolist(), 'expected': np.concatenate(scores, axis=1).tolist(),
            'invalidModels': {name: base64.b64encode(graph.SerializeToString()).decode() for name, graph in variants.items()}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', required=True, type=Path)
    parser.add_argument('--metadata', required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(build_fixture(args.model, args.metadata), indent=2, allow_nan=False))


if __name__ == '__main__':
    main()

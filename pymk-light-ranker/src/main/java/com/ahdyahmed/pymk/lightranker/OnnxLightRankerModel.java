package com.ahdyahmed.pymk.lightranker;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** ONNX Runtime adapter with strict Day 19 metadata and tensor-contract checks. */
public final class OnnxLightRankerModel implements LightRankerModel {

    private static final String EXPECTED_DTYPE = "float32";
    private static final int POSITIVE_CLASS = 1;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final String probabilityOutputName;
    private final String modelVersion;

    private OnnxLightRankerModel(
            OrtEnvironment environment,
            OrtSession session,
            String inputName,
            String probabilityOutputName,
            String modelVersion) {
        this.environment = environment;
        this.session = session;
        this.inputName = inputName;
        this.probabilityOutputName = probabilityOutputName;
        this.modelVersion = modelVersion;
    }

    public static OnnxLightRankerModel load(
            Path modelPath,
            Path metadataPath,
            ObjectMapper objectMapper) {
        try {
            byte[] modelBytes = Files.readAllBytes(modelPath.toAbsolutePath().normalize());
            JsonNode metadata = objectMapper.readTree(
                    Files.readAllBytes(metadataPath.toAbsolutePath().normalize()));
            MetadataContract contract = validateMetadata(metadata, modelBytes);

            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            OrtSession session = null;
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                session = environment.createSession(modelBytes, options);
                String probabilityOutput = validateRuntimeContract(session, contract.inputName());
                return new OnnxLightRankerModel(
                        environment, session, contract.inputName(), probabilityOutput,
                        contract.modelVersion());
            } catch (RuntimeException | OrtException exception) {
                if (session != null) {
                    session.close();
                }
                throw exception;
            }
        } catch (IOException exception) {
            throw new LightRankerException("Unable to read the L1 model artifact: " + exception.getMessage(), exception);
        } catch (OrtException exception) {
            throw new LightRankerException("Unable to load the L1 ONNX model: " + exception.getMessage(), exception);
        }
    }

    @Override
    public float[] score(float[][] features) {
        validateFeatureMatrix(features);
        try (OnnxTensor input = OnnxTensor.createTensor(environment, features);
                OrtSession.Result result = session.run(Map.of(inputName, input))) {
            OnnxValue output = result.get(probabilityOutputName)
                    .orElseThrow(() -> new LightRankerException(
                            "ONNX probability output disappeared: " + probabilityOutputName));
            if (!(output.getValue() instanceof float[][] probabilities)
                    || probabilities.length != features.length) {
                throw new LightRankerException("ONNX probability output has an unexpected batch shape");
            }
            float[] positiveProbabilities = new float[probabilities.length];
            for (int row = 0; row < probabilities.length; row++) {
                if (probabilities[row].length != 2) {
                    throw new LightRankerException("ONNX probability output must contain two classes");
                }
                float probability = probabilities[row][POSITIVE_CLASS];
                if (!Float.isFinite(probability) || probability < 0.0f || probability > 1.0f) {
                    throw new LightRankerException("ONNX returned an invalid positive-class probability");
                }
                positiveProbabilities[row] = probability;
            }
            return positiveProbabilities;
        } catch (OrtException exception) {
            throw new LightRankerException("L1 ONNX inference failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public String modelVersion() {
        return modelVersion;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException exception) {
            throw new LightRankerException("Unable to close the L1 ONNX session", exception);
        }
    }

    private static MetadataContract validateMetadata(JsonNode metadata, byte[] modelBytes) {
        if (metadata == null || !metadata.isObject()) {
            throw contractError("metadata must be a JSON object");
        }
        if (metadata.path("schemaVersion").asInt(-1) != 1) {
            throw contractError("schemaVersion must equal 1");
        }
        String modelVersion = requiredText(metadata, "modelVersion");
        JsonNode features = metadata.path("features");
        if (!features.isArray()
                || !LightRankerModel.FEATURE_NAMES.equals(
                        java.util.stream.StreamSupport.stream(features.spliterator(), false)
                                .map(JsonNode::asText)
                                .toList())) {
            throw contractError("features must exactly match the Day 19 serving order");
        }
        JsonNode tensor = metadata.path("inputTensor");
        String inputName = requiredText(tensor, "name");
        if (!EXPECTED_DTYPE.equals(tensor.path("dtype").asText())) {
            throw contractError("inputTensor.dtype must equal float32");
        }
        JsonNode shape = tensor.path("shape");
        if (!shape.isArray() || shape.size() != 2 || !shape.get(0).isNull()
                || shape.get(1).asInt(-1) != FEATURE_NAMES.size()) {
            throw contractError("inputTensor.shape must equal [null, 6]");
        }
        if (metadata.path("positiveClass").asInt(-1) != POSITIVE_CLASS) {
            throw contractError("positiveClass must equal 1");
        }
        String expectedHash = requiredText(metadata.path("onnx"), "sha256");
        String actualHash = sha256(modelBytes);
        if (!expectedHash.equalsIgnoreCase(actualHash)) {
            throw contractError("ONNX SHA-256 does not match metadata");
        }
        return new MetadataContract(modelVersion, inputName);
    }

    private static String validateRuntimeContract(OrtSession session, String inputName)
            throws OrtException {
        Map<String, NodeInfo> inputs = session.getInputInfo();
        if (inputs.size() != 1 || !inputs.containsKey(inputName)) {
            throw contractError("ONNX model must expose only the declared input tensor");
        }
        requireFloatTensor(inputs.get(inputName), 2, -1, FEATURE_NAMES.size(), "input");

        Map<String, NodeInfo> outputs = session.getOutputInfo();
        if (outputs.size() != 2) {
            throw contractError("ONNX model must expose label and probability outputs");
        }
        String probabilityOutput = outputs.entrySet().stream()
                .filter(entry -> isFloatTensor(entry.getValue(), 2, -1, 2))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> contractError(
                        "ONNX model must expose a float32 [batch, 2] probability output"));
        boolean hasLabelOutput = outputs.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(probabilityOutput))
                .map(Map.Entry::getValue)
                .anyMatch(OnnxLightRankerModel::isLabelTensor);
        if (!hasLabelOutput) {
            throw contractError("ONNX model must expose an int64 [batch] label output");
        }
        return probabilityOutput;
    }

    private static void requireFloatTensor(
            NodeInfo node, int dimensions, long first, long second, String description) {
        if (!isFloatTensor(node, dimensions, first, second)) {
            throw contractError("ONNX " + description + " tensor contract is incompatible");
        }
    }

    private static boolean isFloatTensor(
            NodeInfo node, int dimensions, long first, long second) {
        if (!(node.getInfo() instanceof TensorInfo tensorInfo)) {
            return false;
        }
        long[] shape = tensorInfo.getShape();
        return tensorInfo.type == OnnxJavaType.FLOAT
                && shape.length == dimensions
                && shape[0] == first
                && shape[1] == second;
    }

    private static boolean isLabelTensor(NodeInfo node) {
        if (!(node.getInfo() instanceof TensorInfo tensorInfo)) {
            return false;
        }
        long[] shape = tensorInfo.getShape();
        return tensorInfo.type == OnnxJavaType.INT64
                && shape.length == 1
                && shape[0] == -1;
    }

    private static void validateFeatureMatrix(float[][] features) {
        if (features == null || features.length == 0) {
            throw new IllegalArgumentException("features must contain at least one row");
        }
        for (float[] row : features) {
            if (row == null || row.length != FEATURE_NAMES.size()) {
                throw new IllegalArgumentException("each feature row must contain exactly 6 values");
            }
            for (float value : row) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("feature values must be finite");
                }
            }
        }
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw contractError(field + " must be a non-blank string");
        }
        return value.asText();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static LightRankerException contractError(String detail) {
        return new LightRankerException("Invalid L1 model contract: " + detail);
    }

    private record MetadataContract(String modelVersion, String inputName) {}
}

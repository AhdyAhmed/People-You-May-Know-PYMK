package com.ahdyahmed.pymk.heavyranker;

import ai.onnxruntime.*;
import com.ahdyahmed.pymk.lightranker.LightRankerModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Day 25's exact named two-head tensor contract, with safe concurrent inference/close. */
public final class OnnxHeavyRankerModel implements HeavyRankerModel {
    private static final String SEMANTICS = "directional-unconditional-(featureAsOf,labelWindowEnd]";
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String version;
    private final Compatibility compatibility;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private boolean closed;
    private String artifactIdentity;

    private OnnxHeavyRankerModel(OrtEnvironment environment, OrtSession session, String version, Compatibility compatibility) {
        this.environment = environment; this.session = session; this.version = version; this.compatibility = compatibility;
    }

    public static OnnxHeavyRankerModel load(Path model, Path metadata, ObjectMapper mapper) {
        try { return load(Files.readAllBytes(model), mapper.readTree(Files.readAllBytes(metadata))); }
        catch (java.io.IOException ex) { throw new HeavyRankerException("Cannot read L2 artifacts", ex); }
    }

    public static OnnxHeavyRankerModel load(byte[] bytes, JsonNode metadata) {
        OrtSession session = null;
        try {
            Compatibility compatibility = validateMetadata(bytes, metadata);
            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            try (var options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(1);
                options.setInterOpNumThreads(1);
                session = environment.createSession(bytes, options);
            }
            var inputs = session.getInputInfo();
            var outputs = session.getOutputInfo();
            require(inputs.keySet().equals(Set.of("features")), "ONNX input names");
            requireTensor(inputs.get("features"), 6);
            require(outputs.keySet().equals(new HashSet<>(OUTPUTS)), "ONNX output names");
            for (String output : OUTPUTS) requireTensor(outputs.get(output), 1);
            var model = new OnnxHeavyRankerModel(environment, session, metadata.path("modelVersion").asText(), compatibility);
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes);
            digest.update(metadata.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            model.artifactIdentity = HexFormat.of().formatHex(digest.digest());
            model.score(new float[][]{new float[6]}); // Detect bad output values before accepting work.
            return model;
        } catch (Exception ex) {
            if (session != null) try { session.close(); } catch (OrtException cleanup) { ex.addSuppressed(cleanup); }
            if (ex instanceof HeavyRankerException known) throw known;
            throw new HeavyRankerException("Invalid L2 model artifact", ex);
        }
    }

    private static Compatibility validateMetadata(byte[] bytes, JsonNode m) throws Exception {
        require(m != null && m.isObject(), "metadata must be an object");
        require(m.path("schemaVersion").isInt() && m.path("schemaVersion").intValue() == 1, "schemaVersion");
        require(m.path("modelType").asText().equals("standard-scaler+two-head-mlp"), "modelType");
        require(m.path("modelVersion").asText().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"), "modelVersion");
        require(m.path("features").isArray(), "feature order");
        var names = new ArrayList<String>(); m.path("features").forEach(n -> names.add(n.asText()));
        require(names.equals(LightRankerModel.FEATURE_NAMES), "feature order");
        requireDescriptor(m.path("inputTensor"), "features", 6);
        var outputs = m.path("outputTensors");
        require(outputs.isArray() && outputs.size() == 2, "two output descriptors are required");
        for (int i = 0; i < 2; i++) {
            requireDescriptor(outputs.get(i), OUTPUTS.get(i), 1);
            require(outputs.get(i).path("target").asText().equals(i == 0 ? "invite_sent" : "invite_accepted"), "output target");
            require(outputs.get(i).path("semantics").asText().equals(SEMANTICS), "output semantics");
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        require(hash.equals(m.path("onnx").path("sha256").asText()), "ONNX checksum");
        require(m.path("onnx").path("opset").asInt() == 17 && m.path("onnx").path("irVersion").asInt() == 8, "ONNX version");
        JsonNode c = m.path("embeddingCompatibility");
        require(c.path("featureSchema").asText().equals("pair-v1"), "feature schema");
        require(c.path("embeddingVersion").asText().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"), "embedding version");
        require(!c.path("embeddingVersion").asText().equals("placeholder-v1"), "profile transform required");
        require(c.path("transformSha256").asText().matches("[0-9a-f]{64}"), "transform checksum");
        Instant fit = Instant.parse(c.path("fitCutoff").asText());
        Instant start = Instant.parse(m.path("split").path("train").path("fromInclusive").asText());
        Instant trainEnd = Instant.parse(m.path("split").path("trainLabelThroughInclusive").asText());
        Instant selection = Instant.parse(m.path("split").path("validationLabelThroughInclusive").asText());
        require(!fit.isAfter(start) && !start.isAfter(trainEnd) && !trainEnd.isAfter(selection), "training cutoffs");
        return new Compatibility("pair-v1", c.path("embeddingVersion").asText(), c.path("transformSha256").asText(), fit, selection);
    }

    private static void requireDescriptor(JsonNode node, String name, int width) {
        var shape = node.path("shape");
        require(node.path("name").asText().equals(name) && node.path("dtype").asText().equals("float32")
                && shape.isArray() && shape.size() == 2 && shape.get(0).isNull()
                && shape.get(1).isInt() && shape.get(1).intValue() == width, "tensor descriptor " + name);
    }

    private static void requireTensor(NodeInfo node, int width) {
        require(node != null && node.getInfo() instanceof TensorInfo, "ONNX tensor type");
        TensorInfo info = (TensorInfo) node.getInfo();
        require(info.type == OnnxJavaType.FLOAT && Arrays.equals(info.getShape(), new long[]{-1, width}), "ONNX dynamic float32 tensor shape");
    }

    private static void require(boolean valid, String detail) {
        if (!valid) throw new HeavyRankerException("Invalid L2 contract: " + detail);
    }

    @Override public List<Probabilities> score(float[][] features) {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new HeavyRankerException("L2 model is closed");
            Objects.requireNonNull(features, "features");
            for (float[] row : features) {
                if (row == null || row.length != 6) throw new IllegalArgumentException("Expected six feature values");
                for (float value : row) if (!Float.isFinite(value)) throw new IllegalArgumentException("Features must be finite");
            }
            if (features.length == 0) return List.of();
            try (var input = OnnxTensor.createTensor(environment, features);
                 var result = session.run(Map.of("features", input))) {
                float[][] sent = output(result, OUTPUTS.get(0), features.length);
                float[][] accepted = output(result, OUTPUTS.get(1), features.length);
                var scores = new ArrayList<Probabilities>(features.length);
                for (int i = 0; i < features.length; i++) scores.add(new Probabilities(sent[i][0], accepted[i][0]));
                return List.copyOf(scores);
            } catch (OrtException ex) { throw new HeavyRankerException("L2 inference failed", ex); }
        } finally { lifecycle.readLock().unlock(); }
    }

    private static float[][] output(OrtSession.Result result, String name, int count) throws OrtException {
        Object value = result.get(name).orElseThrow(() -> new HeavyRankerException("Missing L2 output " + name)).getValue();
        if (!(value instanceof float[][] rows) || rows.length != count) throw new HeavyRankerException("Wrong L2 batch shape");
        for (float[] row : rows) if (row == null || row.length != 1) throw new HeavyRankerException("Wrong L2 head width");
        return rows;
    }

    @Override public String modelVersion() { return version; }
    @Override public String artifactIdentity() { return artifactIdentity; }
    @Override public Compatibility compatibility() { return compatibility; }
    @Override public void close() {
        lifecycle.writeLock().lock();
        try {
            if (!closed) { closed = true; session.close(); }
        } catch (OrtException ex) { throw new HeavyRankerException("Cannot close L2 model", ex); }
        finally { lifecycle.writeLock().unlock(); }
    }
}

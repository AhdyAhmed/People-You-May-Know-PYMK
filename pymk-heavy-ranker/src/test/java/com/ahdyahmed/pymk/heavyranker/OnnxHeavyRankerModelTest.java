package com.ahdyahmed.pymk.heavyranker;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class OnnxHeavyRankerModelTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static JsonNode fixture() throws Exception {
        String external = System.getProperty("pymk.heavy.fixture");
        if (external != null) return MAPPER.readTree(Files.readAllBytes(Path.of(external)));
        try (var input = OnnxHeavyRankerModelTest.class.getResourceAsStream("/heavy-serving-fixture.json")) {
            return MAPPER.readTree(input);
        }
    }
    static byte[] bytes(JsonNode f) { return Base64.getDecoder().decode(f.path("modelBase64").asText()); }

    @Test void actualExportMatchesPythonForWholeBatchAndSingletons() throws Exception {
        var f = fixture();
        var matrix = MAPPER.treeToValue(f.path("features"), float[][].class);
        try (var model = OnnxHeavyRankerModel.load(bytes(f), f.path("metadata"))) {
            var scores = model.score(matrix);
            assertThat(scores).hasSize(matrix.length);
            for (int i = 0; i < matrix.length; i++) {
                assertThat(scores.get(i).inviteSent()).isCloseTo((float) f.path("expected").get(i).get(0).asDouble(), within(1e-6f));
                assertThat(scores.get(i).inviteAccepted()).isCloseTo((float) f.path("expected").get(i).get(1).asDouble(), within(1e-6f));
                var single = model.score(new float[][]{matrix[i]}).getFirst();
                assertThat(single.inviteSent()).isCloseTo(scores.get(i).inviteSent(), within(1e-6f));
                assertThat(single.inviteAccepted()).isCloseTo(scores.get(i).inviteAccepted(), within(1e-6f));
            }
            assertThat(model.score(new float[0][])).isEmpty();
            assertThat(model.modelVersion()).isEqualTo(f.path("metadata").path("modelVersion").asText());
        }
    }

    @Test void rejectsCorruptMetadataAndChecksums() throws Exception {
        var f = fixture();
        for (String field : List.of("schemaVersion", "modelType", "modelVersion", "features", "inputTensor", "outputTensors", "embeddingCompatibility", "split", "onnx")) {
            ObjectNode metadata = f.path("metadata").deepCopy();
            metadata.remove(field);
            assertThatThrownBy(() -> OnnxHeavyRankerModel.load(bytes(f), metadata)).as(field).isInstanceOf(HeavyRankerException.class);
        }
        byte[] corrupted = bytes(f); corrupted[corrupted.length - 1] ^= 1;
        assertThatThrownBy(() -> OnnxHeavyRankerModel.load(corrupted, f.path("metadata"))).hasMessageContaining("checksum");
        for (String pointer : List.of("/features/0", "/inputTensor/name", "/inputTensor/dtype", "/outputTensors/0/target", "/outputTensors/1/semantics", "/embeddingCompatibility/transformSha256", "/embeddingCompatibility/fitCutoff")) {
            ObjectNode metadata = f.path("metadata").deepCopy();
            int split = pointer.lastIndexOf('/');
            JsonNode parent = metadata.at(pointer.substring(0, split));
            String key = pointer.substring(split + 1);
            if (parent.isArray()) ((com.fasterxml.jackson.databind.node.ArrayNode) parent).set(Integer.parseInt(key), MAPPER.getNodeFactory().textNode("wrong"));
            else ((ObjectNode) parent).put(key, "wrong");
            assertThatThrownBy(() -> OnnxHeavyRankerModel.load(bytes(f), metadata)).as(pointer).isInstanceOf(HeavyRankerException.class);
        }
    }

    @Test void rejectsInvalidNativeContractsAndProbabilityThenCanLoadAgain() throws Exception {
        var f = fixture();
        var variants = f.path("invalidModels").fields();
        while (variants.hasNext()) {
            var variant = variants.next();
            byte[] invalid = Base64.getDecoder().decode(variant.getValue().asText());
            ObjectNode metadata = f.path("metadata").deepCopy();
            ((ObjectNode) metadata.path("onnx")).put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(invalid)));
            assertThatThrownBy(() -> OnnxHeavyRankerModel.load(invalid, metadata)).as(variant.getKey()).isInstanceOf(HeavyRankerException.class);
        }
        try (var valid = OnnxHeavyRankerModel.load(bytes(f), f.path("metadata"))) {
            assertThat(valid.score(new float[][]{new float[6]})).hasSize(1);
        }
    }

    @Test void validatesInputsAndProbabilityBounds() throws Exception {
        var f = fixture();
        try (var model = OnnxHeavyRankerModel.load(bytes(f), f.path("metadata"))) {
            assertThatThrownBy(() -> model.score(null)).isInstanceOf(NullPointerException.class);
            for (float[][] invalid : List.of(new float[][]{null}, new float[][]{new float[5]}, new float[][]{{0,0,0,0,Float.NaN,0}}, new float[][]{{0,0,0,0,Float.POSITIVE_INFINITY,0}})) {
                assertThatThrownBy(() -> model.score(invalid)).isInstanceOf(IllegalArgumentException.class);
            }
        }
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f}) {
            assertThatThrownBy(() -> new HeavyRankerModel.Probabilities(value, 0)).isInstanceOf(HeavyRankerException.class);
            assertThatThrownBy(() -> new HeavyRankerModel.Probabilities(0, value)).isInstanceOf(HeavyRankerException.class);
        }
    }

    @Test void closeIsIdempotentAndDoesNotCloseSharedEnvironment() throws Exception {
        var f = fixture();
        var first = OnnxHeavyRankerModel.load(bytes(f), f.path("metadata"));
        try (first; var second = OnnxHeavyRankerModel.load(bytes(f), f.path("metadata"))) {
            first.close(); first.close();
            assertThatThrownBy(() -> first.score(new float[0][])).hasMessageContaining("closed");
            assertThat(second.score(new float[][]{new float[6]})).hasSize(1);
        }
    }

    @Test void concurrentInferenceAndCloseAreSafe() throws Exception {
        var f = fixture();
        try (var model = OnnxHeavyRankerModel.load(bytes(f), f.path("metadata")); var pool = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 3; worker++) futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 50; i++) {
                    try { assertThat(model.score(new float[][]{new float[6]})).hasSize(1); }
                    catch (HeavyRankerException ex) { assertThat(ex).hasMessageContaining("closed"); }
                }
                return null;
            }));
            futures.add(pool.submit(() -> { start.await(); model.close(); return null; }));
            start.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
    }
}

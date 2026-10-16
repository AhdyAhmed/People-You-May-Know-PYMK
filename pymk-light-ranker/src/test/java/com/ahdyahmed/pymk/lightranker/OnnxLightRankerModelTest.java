package com.ahdyahmed.pymk.lightranker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnnxLightRankerModelTest {

    private static final String MODEL_BASE64 = "CAgSCHNrbDJvbm54GgYxLjIwLjAiB2FpLm9ubngoADIAOvcDCoUBCghmZWF0dXJlcxIIdmFyaWFibGUaBlNjYWxlciIGU2NhbGVyKikKBm9mZnNldD0AAGBAPQAAAD89AAAAPz0AAAA/PZqZGb49AABAQKABBiooCgVzY2FsZT3VAA4/PQAAAEA9AAAAQD0AAABAPabPPkA9LvnkPqABBjoKYWkub25ueC5tbAqIAgoIdmFyaWFibGUSBWxhYmVsEg1wcm9iYWJpbGl0aWVzGhBMaW5lYXJDbGFzc2lmaWVyIhBMaW5lYXJDbGFzc2lmaWVyKhkKEGNsYXNzbGFiZWxzX2ludHNAAEABoAEHKk0KDGNvZWZmaWNpZW50cz16vYi9Pfa6oz8947cXMT32uqO/PbgfQL49tR9Avj16vYg9Pfa6o78947cXsT32uqM/PbgfQD49tR9APqABBioZCgppbnRlcmNlcHRzPZ6KAzI9nooDsqABBioSCgttdWx0aV9jbGFzcxgAoAECKh0KDnBvc3RfdHJhbnNmb3JtIghMT0dJU1RJQ6ABAzoKYWkub25ueC5tbBIWbGlnaHQtbG9ncmVnLWphdmEtdGVzdFoYCghmZWF0dXJlcxIMCgoIARIGCgAKAggGYhEKBWxhYmVsEggKBggHEgIKAGIdCg1wcm9iYWJpbGl0aWVzEgwKCggBEgYKAAoCCAJCDgoKYWkub25ueC5tbBABQgQKABARQgQKABAR";

    @TempDir Path temporaryDirectory;

    @Test
    void loadsTheDay19ArtifactContractAndRunsRealBatchedInference() throws Exception {
        byte[] modelBytes = Base64.getDecoder().decode(MODEL_BASE64);
        Path modelPath = temporaryDirectory.resolve("model.onnx");
        Path metadataPath = temporaryDirectory.resolve("model.metadata.json");
        Files.write(modelPath, modelBytes);
        Files.writeString(metadataPath, metadata(sha256(modelBytes), LightRankerModel.FEATURE_NAMES));

        try (OnnxLightRankerModel model = OnnxLightRankerModel.load(
                modelPath, metadataPath, new ObjectMapper())) {
            float[] scores = model.score(new float[][] {
                {4, 1, 0, 1, 0.75f, 7},
                {5, 0, 0, 1, 0.5f, 8}
            });

            assertThat(model.modelVersion()).isEqualTo("light-logreg-java-test");
            assertThat(scores).hasSize(2);
            assertThat(scores[0]).isCloseTo(0.7021557f, within(0.000001f));
            assertThat(scores[1]).isCloseTo(0.96760964f, within(0.000001f));
        }
    }

    @Test
    void rejectsChecksumAndFeatureOrderMismatchesBeforeServing() throws Exception {
        byte[] modelBytes = Base64.getDecoder().decode(MODEL_BASE64);
        Path modelPath = temporaryDirectory.resolve("model.onnx");
        Path metadataPath = temporaryDirectory.resolve("model.metadata.json");
        Files.write(modelPath, modelBytes);
        Files.writeString(metadataPath, metadata("0".repeat(64), LightRankerModel.FEATURE_NAMES));

        assertThatThrownBy(() -> OnnxLightRankerModel.load(
                        modelPath, metadataPath, new ObjectMapper()))
                .isInstanceOf(LightRankerException.class)
                .hasMessageContaining("SHA-256");

        Files.writeString(metadataPath, metadata(
                sha256(modelBytes),
                java.util.List.of(
                        "same_company", "mutual_connection_count", "same_school",
                        "same_geo", "embedding_cosine_sim", "profile_view_count_last_30d")));
        assertThatThrownBy(() -> OnnxLightRankerModel.load(
                        modelPath, metadataPath, new ObjectMapper()))
                .isInstanceOf(LightRankerException.class)
                .hasMessageContaining("serving order");
    }

    private static org.assertj.core.data.Offset<Float> within(float tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }

    private static String metadata(String hash, java.util.List<String> features) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("modelVersion", "light-logreg-java-test");
        root.set("features", mapper.valueToTree(features));
        var input = root.putObject("inputTensor");
        input.put("name", "features");
        input.put("dtype", "float32");
        input.putArray("shape").addNull().add(6);
        root.put("positiveClass", 1);
        root.putObject("onnx").put("sha256", hash);
        return mapper.writeValueAsString(root);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}

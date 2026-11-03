package com.ahdyahmed.pymk.batch;

import static org.assertj.core.api.Assertions.*;
import com.ahdyahmed.pymk.lightranker.LightRankerModel;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class RegistryReleaseContractTest {
    static void attach(ObjectNode root) {
        root.put("schemaVersion", 2);
        var extension = root.putObject("registry");
        extension.put("schemaVersion", 1).put("automaticPromotion", false);
        var model = extension.putObject("models").putObject("light");
        model.put("schemaVersion", 1).put("kind", "light").put("modelVersion", "example-v1")
                .put("name", "pymk.light.example-v1").put("version", "1").put("runId", "a".repeat(32))
                .put("candidateSha256", "b".repeat(64)).put("sourceUri", "mlflow-artifacts:/candidate/" + "b".repeat(64))
                .put("artifactUri", "models:/pymk.light.example-v1/1").put("automaticPromotion", false);
        var compatibility = model.putObject("compatibility").put("transformSha256", root.path("transformSha256").asText()).put("featureSchema", "pair-v1")
                .put("embeddingVersion", "profile-v1").put("fitCutoff", "2026-01-03T00:00:00Z");
        if (root.has("transformPath")) {
            try {
                var metadata = EmbeddingReleaseManifest.JSON.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(root.path("transformPath").asText()))).path("payload").path("metadata");
                compatibility.put("embeddingVersion", metadata.path("embeddingVersion").asText()).put("fitCutoff", metadata.path("fitCutoff").asText());
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        }
        model.putArray("trackingRunIds").add("d".repeat(32));
        model.set("features", EmbeddingReleaseManifest.JSON.valueToTree(LightRankerModel.FEATURE_NAMES));
        var files = model.putObject("files");
        for (String name : new String[]{"model.onnx", "metadata.json", "java-validation.json", "parity.json", "dataset.json", "tracking.json"}) files.put(name, "c".repeat(64));
        files.put("transform.json", root.path("transformSha256").asText());
    }

    @Test void legacyAndPinnedContractsAreAccepted() {
        var root = EmbeddingReleaseManifest.JSON.createObjectNode().put("schemaVersion", 1).put("transformSha256", "a".repeat(64));
        RegistryReleaseContract.validate(root);
        attach(root);
        RegistryReleaseContract.validate(root);
    }

    @Test void aliasesMissingDigestsAndWrongFeatureContractsFailClosed() {
        for (String field : new String[]{"version", "artifactUri", "files", "features", "runId", "candidateSha256", "compatibility", "trackingRunIds"}) {
            var root = EmbeddingReleaseManifest.JSON.createObjectNode().put("transformSha256", "a".repeat(64));
            attach(root);
            ((ObjectNode) root.at("/registry/models/light")).put(field, "champion");
            assertThatThrownBy(() -> RegistryReleaseContract.validate(root)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void legacyCannotSilentlyIgnoreRegistryExtension() {
        var root = EmbeddingReleaseManifest.JSON.createObjectNode().put("transformSha256", "a".repeat(64));
        attach(root); root.put("schemaVersion", 1);
        assertThatThrownBy(() -> RegistryReleaseContract.validate(root)).hasMessageContaining("schema 2");
        root.put("schemaVersion", 3);
        assertThatThrownBy(() -> RegistryReleaseContract.validate(root)).hasMessageContaining("schemaVersion");
    }
}

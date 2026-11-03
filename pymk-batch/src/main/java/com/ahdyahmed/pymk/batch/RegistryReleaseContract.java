package com.ahdyahmed.pymk.batch;

import com.ahdyahmed.pymk.lightranker.LightRankerModel;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Versioned registry references are pinned evidence, never mutable alias lookups. */
final class RegistryReleaseContract {
    private RegistryReleaseContract() {}

    static void validate(JsonNode root) {
        int schema = root.path("schemaVersion").asInt();
        if (!root.path("schemaVersion").isInt() || (schema != 1 && schema != 2))
            throw new IllegalArgumentException("Manifest schemaVersion must be 1 or 2");
        if (schema == 1) {
            require(!root.has("registry"), "Registry references require schema 2");
            return;
        }
        var registry = root.path("registry");
        require(registry.path("schemaVersion").isInt() && registry.path("schemaVersion").asInt() == 1
                && registry.path("automaticPromotion").isBoolean()
                && !registry.path("automaticPromotion").asBoolean(), "Invalid registry extension");
        var models = registry.path("models");
        require(models.isObject() && models.has("light"), "Registry release requires L1");
        models.fieldNames().forEachRemaining(kind -> {
            require(Set.of("light", "heavy").contains(kind), "Unexpected registry model role");
            var model = models.path(kind);
            String version = model.path("version").asText();
            String name = model.path("name").asText();
            EmbeddingReleaseManifest.requireId(model.path("modelVersion").asText());
            require(model.path("schemaVersion").isInt() && model.path("schemaVersion").asInt() == 1 && kind.equals(model.path("kind").asText())
                    && name.equals("pymk." + kind + "." + model.path("modelVersion").asText())
                    && model.path("version").isTextual() && version.matches("[1-9][0-9]*")
                    && model.path("artifactUri").asText().equals("models:/" + name + "/" + version)
                    && model.path("runId").asText().matches("[a-f0-9]{32}")
                    && model.path("sourceUri").asText().contains("/candidate/" + model.path("candidateSha256").asText())
                    && model.path("candidateSha256").asText().matches("[a-f0-9]{64}")
                    && model.path("automaticPromotion").isBoolean() && !model.path("automaticPromotion").asBoolean(),
                    "Registry model must pin an exact version, run and candidate");
            var compatibility = model.path("compatibility");
            EmbeddingReleaseManifest.requireId(compatibility.path("embeddingVersion").asText());
            java.time.Instant.parse(compatibility.path("fitCutoff").asText());
            require(compatibility.path("transformSha256").asText().equals(root.path("transformSha256").asText())
                    && compatibility.path("featureSchema").asText().equals("pair-v1")
                    && model.path("features").equals(EmbeddingReleaseManifest.JSON.valueToTree(LightRankerModel.FEATURE_NAMES)),
                    "Registry compatibility mismatch");
            var runs = model.path("trackingRunIds");
            require(runs.isArray() && !runs.isEmpty(), "Missing source tracking runs");
            for (var run : runs) require(run.isTextual() && run.asText().matches("[a-f0-9]{32}"), "Invalid source tracking run");
            for (String file : Set.of("model.onnx", "metadata.json", "transform.json", "java-validation.json", "parity.json", "dataset.json", "tracking.json"))
                require(model.path("files").path(file).asText().matches("[a-f0-9]{64}"), "Missing registry artifact digest");
            require(model.path("files").path("transform.json").asText().equals(root.path("transformSha256").asText()), "Registry transform mismatch");
        });
        if (models.has("heavy")) {
            var light = models.path("light").path("files");
            var comparison = models.path("heavy").path("l1");
            require(comparison.path("onnxSha256").asText().equals(light.path("model.onnx").asText())
                    && comparison.path("metadataSha256").asText().equals(light.path("metadata.json").asText()), "Registry L1/L2 mismatch");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

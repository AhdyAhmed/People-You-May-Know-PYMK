package com.ahdyahmed.pymk.batch;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;

/** Local operator trust boundary, not a signature or remote authorization system. */
public final class PromotionApproval {
    private PromotionApproval() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("Expected stage evidence.json operator expectedEpoch expiresAt");
        approve(Path.of(args[0]), Path.of(args[1]), args[2], Long.parseLong(args[3]), Instant.parse(args[4]), Instant.now());
    }

    static void approve(Path stage, Path evidence, String operator, long epoch, Instant expires, Instant now) throws Exception {
        stage = stage.toAbsolutePath().normalize();
        require(operator != null && operator.matches("[A-Za-z0-9][A-Za-z0-9@._-]{1,99}"), "Explicit operator identity required");
        require(epoch >= 0 && expires.isAfter(now) && !expires.isAfter(now.plusSeconds(86400)), "Approval expires within 24 hours");
        validateStage(stage);
        var reviewedEvidence = read(evidence);
        validateEvidence(stage, reviewedEvidence, now);
        var approval = EmbeddingReleaseManifest.JSON.createObjectNode();
        approval.put("schemaVersion", 1).put("operator", operator).put("expectedEpoch", epoch)
                .put("approvedAt", now.toString()).put("expiresAt", expires.toString())
                .put("syncSha256", EmbeddingReleaseManifest.fileHash(stage.resolve("sync.json")))
                .put("manifestSha256", EmbeddingReleaseManifest.fileHash(stage.resolve("release.json")));
        approval.set("evidence", reviewedEvidence);
        Files.write(stage.resolve("approval.json"), EmbeddingReleaseManifest.JSON.writeValueAsBytes(approval), StandardOpenOption.CREATE_NEW);
    }

    static JsonNode verify(Path stage, long epoch, Instant now) throws Exception {
        stage = stage.toAbsolutePath().normalize();
        var approval = read(stage.resolve("approval.json"));
        require(approval.path("schemaVersion").asInt() == 1 && approval.path("expectedEpoch").isIntegralNumber()
                && approval.path("expectedEpoch").asLong() == epoch, "Stale approval epoch");
        require(approval.path("operator").asText().matches("[A-Za-z0-9][A-Za-z0-9@._-]{1,99}"), "Missing operator approval");
        Instant approved = Instant.parse(approval.path("approvedAt").asText());
        Instant expires = Instant.parse(approval.path("expiresAt").asText());
        require(!approved.isAfter(now) && expires.isAfter(now) && expires.isAfter(approved)
                && !expires.isAfter(approved.plusSeconds(86400)), "Expired or invalid approval");
        EmbeddingReleaseManifest.verify(stage.resolve("sync.json"), approval.path("syncSha256").asText());
        EmbeddingReleaseManifest.verify(stage.resolve("release.json"), approval.path("manifestSha256").asText());
        validateStage(stage);
        validateEvidence(stage, approval.path("evidence"), now);
        return read(stage.resolve("release.json"));
    }

    private static void validateStage(Path stage) throws Exception {
        var sync = read(stage.resolve("sync.json"));
        require(sync.path("schemaVersion").asInt() == 1 && sync.path("files").isObject(), "Incomplete synchronization");
        Iterator<Map.Entry<String, JsonNode>> entries = sync.path("files").fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            require(entry.getKey().matches("(?:light/|heavy/)?[a-z-]+\\.(json|onnx|properties|csv)"), "Unsafe sync path");
            EmbeddingReleaseManifest.verify(stage.resolve(entry.getKey()), entry.getValue().asText());
        }
        var manifest = EmbeddingReleaseManifest.read(stage.resolve("release.json").toString());
        require(manifest.json().path("schemaVersion").asInt() == 2, "Registry approval requires schema 2");
        for (String file : java.util.Set.of("release.json", "vectors.csv", "transform.json"))
            require(sync.path("files").has(file), "Incomplete sync inventory");
        require(manifest.vectors().equals(stage.toAbsolutePath().resolve("vectors.csv").normalize())
                && manifest.transform().equals(stage.toAbsolutePath().resolve("transform.json").normalize()), "Unbound release paths");
        var models = manifest.json().path("registry").path("models");
        for (String kind : java.util.List.of("light", "heavy")) {
            if (!models.has(kind)) continue;
            var model = models.path(kind);
            var metadata = read(stage.resolve(kind + "/metadata.json"));
            require(metadata.path("modelVersion").equals(model.path("modelVersion")), "Pinned model identity mismatch");
            for (String field : java.util.List.of("transformSha256", "embeddingVersion", "featureSchema"))
                require(metadata.path("embeddingCompatibility").path(field).equals(model.path("compatibility").path(field)), "Pinned model compatibility mismatch");
            require(Instant.parse(metadata.path("embeddingCompatibility").path("fitCutoff").asText())
                    .equals(manifest.fitCutoff()), "Pinned model cutoff mismatch");
            validateModelTime(metadata, manifest.snapshot(), kind.equals("heavy"));
            Iterator<Map.Entry<String, JsonNode>> files = model.path("files").fields();
            while (files.hasNext()) {
                var file = files.next();
                require(sync.path("files").path(kind + "/" + file.getKey()).equals(file.getValue()), "Unbound model inventory");
            }
            // Validate with production loaders at approval AND activation, without rewriting evidence.
            Path proof = Files.createTempFile("pymk-promotion-", ".json");
            try {
                Files.delete(proof);
                RegistryModelVerifier.verify(stage.resolve(kind), proof);
            } finally { Files.deleteIfExists(proof); }
        }
    }

    private static void validateEvidence(Path stage, JsonNode evidence, Instant now) throws Exception {
        require(evidence.path("schemaVersion").asInt() == 1, "Invalid evidence schema");
        EmbeddingReleaseManifest.verify(stage.resolve("release.json"), evidence.path("manifestSha256").asText());
        Instant measured = Instant.parse(evidence.path("measuredAt").asText());
        require(!measured.isAfter(now) && !measured.isBefore(now.minusSeconds(86400)), "Stale rehearsal evidence");
        require(evidence.path("environment").isTextual() && !evidence.path("environment").asText().isBlank(), "Rehearsal environment required");
        require(evidence.path("cohortSha256").asText().matches("[a-f0-9]{64}"), "Frozen cohort identity required");
        String mode = evidence.path("mode").asText();
        require(java.util.Set.of("L1", "L2", "RERANK").contains(mode), "Invalid deployment mode");
        // These measurements are operator-reviewed rehearsal evidence, not inferred from training success.
        minimum(evidence, "coverage", 1, 1);
        minimum(evidence, "quality", 0.5, 1);
        minimum(evidence, "requesters", 30, Double.MAX_VALUE);
        require(evidence.path("requesters").path("observed").isIntegralNumber()
                && evidence.path("requesters").path("minimum").isIntegralNumber(), "Requester counts must be integers");
        maximum(evidence, "coldP95Ms");
        maximum(evidence, "warmP95Ms");
        require(evidence.path("temporalValid").isBoolean() && evidence.path("temporalValid").asBoolean()
                && evidence.path("rollbackVerified").isBoolean() && evidence.path("rollbackVerified").asBoolean(), "Temporal and rollback rehearsal required");
        for (String config : java.util.List.of("deployment.properties", "rollback.properties"))
            EmbeddingReleaseManifest.verify(stage.resolve(config), evidence.path(config + "Sha256").asText());
        var rollback = new java.util.Properties();
        try (var input = Files.newInputStream(stage.resolve("rollback.properties"))) { rollback.load(input); }
        require("L1".equals(rollback.getProperty("pymk.serving.ranker"))
                && "false".equals(rollback.getProperty("pymk.heavy-ranker.enabled")), "Pinned L1-only fallback required");
        var properties = new java.util.Properties();
        try (var input = Files.newInputStream(stage.resolve("deployment.properties"))) { properties.load(input); }
        require(mode.equals(properties.getProperty("pymk.serving.ranker")), "Deployment mode mismatch");
        for (String budget : java.util.List.of("pymk.light-ranker.max-candidates", "pymk.heavy-ranker.max-candidates"))
            require(Integer.parseInt(properties.getProperty(budget, "0")) > 0, "Explicit candidate budgets required");
        if (!mode.equals("L1")) {
            var models = read(stage.resolve("release.json")).path("registry").path("models");
            require(models.has("heavy"), "Heavy mode requires pinned L2");
            require("true".equals(properties.getProperty("pymk.heavy-ranker.enabled"))
                    && Path.of(properties.getProperty("pymk.heavy-ranker.model-path", "")).toAbsolutePath().normalize().equals(stage.toAbsolutePath().resolve("heavy/model.onnx"))
                    && Path.of(properties.getProperty("pymk.heavy-ranker.metadata-path", "")).toAbsolutePath().normalize().equals(stage.toAbsolutePath().resolve("heavy/metadata.json")), "Unbound heavy deployment paths");
            require(models.path("heavy").path("files").has("evaluate.json"), "Pinned evaluation required");
            var evaluation = read(stage.resolve("heavy/evaluate.json"));
            require("met".equals(evaluation.path("qualityGate").path("status").asText()), "Offline quality gate not met");
            double auc = evaluation.path("partitions").path("test").path("exportedPool").path("heavyAccepted").path("rocAuc").asDouble(Double.NaN);
            require(Double.isFinite(auc) && Math.abs(auc - evidence.path("quality").path("observed").asDouble()) < 1e-12,
                    "Heavy quality must match pinned test acceptance AUC");
            if (mode.equals("RERANK")) {
                require(models.path("heavy").path("files").has("blend.properties"), "Pinned blend required");
                var blend = new java.util.Properties();
                try (var input = Files.newInputStream(stage.resolve("heavy/blend.properties"))) { blend.load(input); }
                for (String name : blend.stringPropertyNames()) require(blend.getProperty(name).equals(properties.getProperty(name)), "Blend deployment mismatch");
            }
        } else {
            require("false".equals(properties.getProperty("pymk.heavy-ranker.enabled")), "L1 fallback must disable heavy scorer");
            double auc = read(stage.resolve("light/metadata.json")).path("metrics").path("validation").path("rocAuc").asDouble(Double.NaN);
            require(Double.isFinite(auc) && Math.abs(auc - evidence.path("quality").path("observed").asDouble()) < 1e-12,
                    "L1 quality must match pinned validation AUC");
        }
    }

    private static void minimum(JsonNode evidence, String name, double floor, double ceiling) {
        var gate = evidence.path(name);
        double observed = gate.path("observed").asDouble(Double.NaN), threshold = gate.path("minimum").asDouble(Double.NaN);
        require(gate.path("observed").isNumber() && gate.path("minimum").isNumber() && Double.isFinite(observed)
                && Double.isFinite(threshold) && threshold >= floor && observed >= threshold && observed <= ceiling, "Failed " + name + " gate");
    }

    static void validateModelTime(JsonNode metadata, Instant snapshot, boolean heavy) {
        for (String end : java.util.List.of("trainLabelThroughInclusive", "validationLabelThroughInclusive"))
            require(!Instant.parse(metadata.path("split").path(end).asText()).isAfter(snapshot), "Model selection exceeds serving snapshot");
        if (heavy) {
            require(metadata.path("inputs").isArray() && !metadata.path("inputs").isEmpty(), "Missing heavy training provenance");
            for (var input : metadata.path("inputs"))
                require(!Instant.parse(input.path("provenance").path("labelWindowEnd").asText()).isAfter(snapshot), "Heavy outcomes exceed serving snapshot");
        }
    }

    private static void maximum(JsonNode evidence, String name) {
        var gate = evidence.path(name);
        double observed = gate.path("observed").asDouble(Double.NaN), threshold = gate.path("maximum").asDouble(Double.NaN);
        require(gate.path("observed").isNumber() && gate.path("maximum").isNumber() && Double.isFinite(observed)
                && Double.isFinite(threshold) && observed > 0 && threshold > 0 && observed <= threshold, "Failed " + name + " gate");
    }

    private static JsonNode read(Path path) throws Exception { return EmbeddingReleaseManifest.JSON.readTree(Files.readAllBytes(path)); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}

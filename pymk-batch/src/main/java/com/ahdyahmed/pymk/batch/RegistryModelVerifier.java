package com.ahdyahmed.pymk.batch;

import com.ahdyahmed.pymk.heavyranker.OnnxHeavyRankerModel;
import com.ahdyahmed.pymk.lightranker.OnnxLightRankerModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Offline parity gate using production Java loaders. No Spring context, DB or network. */
public final class RegistryModelVerifier {
    private RegistryModelVerifier() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected stage directory and evidence output");
        verify(Path.of(args[0]), Path.of(args[1]));
    }

    static void verify(Path stage, Path output) throws Exception {
        var json = EmbeddingReleaseManifest.JSON;
        byte[] model = Files.readAllBytes(stage.resolve("model.onnx"));
        byte[] metadataBytes = Files.readAllBytes(stage.resolve("metadata.json"));
        byte[] fixtureBytes = Files.readAllBytes(stage.resolve("parity.json"));
        var metadata = json.readTree(metadataBytes);
        var fixture = json.readTree(fixtureBytes);
        var features = json.treeToValue(fixture.path("features"), float[][].class);
        var expected = json.treeToValue(fixture.path("expected"), double[][].class);
        if (features == null || features.length < 4 || expected == null || expected.length != features.length)
            throw new IllegalArgumentException("Invalid parity fixture");
        double max = 0;
        String kind = fixture.path("kind").asText();
        if (kind.equals("light")) {
            try (var loaded = OnnxLightRankerModel.load(model, metadata)) {
                float[] scores = loaded.score(features);
                for (int i = 0; i < scores.length; i++) {
                    if (expected[i].length != 1) throw new IllegalArgumentException("Invalid L1 heads");
                    max = Math.max(max, error(scores[i], expected[i][0]));
                    max = Math.max(max, error(loaded.score(new float[][]{features[i]})[0], expected[i][0]));
                }
            }
        } else if (kind.equals("heavy")) {
            try (var loaded = OnnxHeavyRankerModel.load(model, metadata)) {
                var scores = loaded.score(features);
                for (int i = 0; i < scores.size(); i++) {
                    if (expected[i].length != 2) throw new IllegalArgumentException("Invalid L2 heads");
                    var single = loaded.score(new float[][]{features[i]}).getFirst();
                    max = Math.max(max, error(scores.get(i).inviteSent(), expected[i][0]));
                    max = Math.max(max, error(scores.get(i).inviteAccepted(), expected[i][1]));
                    max = Math.max(max, error(single.inviteSent(), expected[i][0]));
                    max = Math.max(max, error(single.inviteAccepted(), expected[i][1]));
                }
            }
        } else throw new IllegalArgumentException("Unknown model kind");
        var evidence = Map.of("schemaVersion", 1, "kind", kind, "rows", features.length,
                "modelSha256", EmbeddingReleaseManifest.sha256(model),
                "metadataSha256", EmbeddingReleaseManifest.sha256(metadataBytes),
                "fixtureSha256", EmbeddingReleaseManifest.sha256(fixtureBytes),
                "maxAbsoluteError", max, "tolerance", 1e-6);
        Files.write(output, json.writeValueAsBytes(evidence), StandardOpenOption.CREATE_NEW);
    }

    private static double error(double actual, double expected) {
        if (!Double.isFinite(actual) || !Double.isFinite(expected) || expected < 0 || expected > 1
                || Math.abs(actual - expected) > 1e-6)
            throw new IllegalArgumentException("Python/Java parity failed");
        return Math.abs(actual - expected);
    }
}

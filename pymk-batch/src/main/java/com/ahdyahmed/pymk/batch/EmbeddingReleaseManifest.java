package com.ahdyahmed.pymk.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** Content-addressed inputs; all relative artifact paths resolve beside the manifest. */
public record EmbeddingReleaseManifest(String id, Instant snapshot, Path vectors, Path transform,
        String vectorsHash, String transformHash, String manifestHash, JsonNode json,
        String embeddingVersion, Instant fitCutoff) {
    static final ObjectMapper JSON = new ObjectMapper();

    static EmbeddingReleaseManifest read(String name) {
        try {
            Path path = Path.of(name).toAbsolutePath().normalize();
            byte[] bytes = Files.readAllBytes(path);
            JsonNode root = JSON.readTree(bytes);
            if (root.path("schemaVersion").asInt() != 1) throw new IllegalArgumentException("Manifest schemaVersion must be 1");
            String id = root.path("releaseId").asText();
            requireId(id);
            Instant snapshot = Instant.parse(root.path("featureAsOf").asText());
            Path vectors = path.getParent().resolve(root.path("vectorsPath").asText()).normalize();
            Path transform = path.getParent().resolve(root.path("transformPath").asText()).normalize();
            String vectorsHash = root.path("vectorsSha256").asText();
            String transformHash = root.path("transformSha256").asText();
            verify(vectors, vectorsHash);
            verify(transform, transformHash);
            JsonNode metadata = JSON.readTree(Files.readAllBytes(transform)).path("payload").path("metadata");
            String version = metadata.path("embeddingVersion").asText();
            requireId(version);
            Instant cutoff = Instant.parse(metadata.path("fitCutoff").asText());
            if ("placeholder-v1".equals(version) || metadata.path("dimensions").asInt() != 128
                    || metadata.path("schemaVersion").asInt() != 1 || cutoff.isAfter(snapshot)) {
                throw new IllegalArgumentException("Incompatible profile transform");
            }
            return new EmbeddingReleaseManifest(id, snapshot, vectors, transform, vectorsHash,
                    transformHash, sha256(bytes), root, version, cutoff);
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("Cannot read embedding manifest", ex); }
    }

    static void requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}") || id.equals("legacy")) {
            throw new IllegalArgumentException("Invalid release/embedding identity");
        }
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    static String fileHash(Path path) throws java.io.IOException {
        try (var input = Files.newInputStream(path)) {
            var hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    static void verify(Path path, String expected) throws java.io.IOException {
        if (!expected.matches("[0-9a-f]{64}") || !expected.equals(fileHash(path))) {
            throw new IllegalArgumentException("Artifact SHA-256 mismatch: " + path);
        }
    }
}

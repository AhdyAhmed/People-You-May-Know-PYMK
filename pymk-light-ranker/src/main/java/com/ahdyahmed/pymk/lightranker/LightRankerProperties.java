package com.ahdyahmed.pymk.lightranker;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Externalized artifact locations and the L1 output bound. */
@ConfigurationProperties("pymk.light-ranker")
public class LightRankerProperties {

    private Path modelPath = Path.of("artifacts/models/light-ranker/light-logreg-v1.onnx");
    private Path metadataPath = Path.of(
            "artifacts/models/light-ranker/light-logreg-v1.metadata.json");
    private int maxCandidates = 500;

    public Path getModelPath() {
        return modelPath;
    }

    public void setModelPath(Path modelPath) {
        this.modelPath = modelPath;
    }

    public Path getMetadataPath() {
        return metadataPath;
    }

    public void setMetadataPath(Path metadataPath) {
        this.metadataPath = metadataPath;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    void validate() {
        if (modelPath == null) {
            throw new IllegalArgumentException("pymk.light-ranker.model-path must be configured");
        }
        if (metadataPath == null) {
            throw new IllegalArgumentException("pymk.light-ranker.metadata-path must be configured");
        }
        if (maxCandidates <= 0) {
            throw new IllegalArgumentException("pymk.light-ranker.max-candidates must be positive");
        }
    }
}

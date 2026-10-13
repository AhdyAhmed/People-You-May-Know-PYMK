package com.ahdyahmed.pymk.batch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Resource bounds for the nightly feature materialization job. */
@Component
@ConfigurationProperties(prefix = "pymk.batch.feature-computation")
public class FeatureComputationProperties {

    private int chunkSize = 10;
    private int fetchSize = 100;
    private int graphCandidateLimit = 2_000;
    private int heuristicCandidateLimit = 1_000;
    private int embeddingCandidateLimit = 1_000;
    private int globalCandidateLimit = 3_000;

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getFetchSize() {
        return fetchSize;
    }

    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    public int getGraphCandidateLimit() {
        return graphCandidateLimit;
    }

    public void setGraphCandidateLimit(int graphCandidateLimit) {
        this.graphCandidateLimit = graphCandidateLimit;
    }

    public int getHeuristicCandidateLimit() {
        return heuristicCandidateLimit;
    }

    public void setHeuristicCandidateLimit(int heuristicCandidateLimit) {
        this.heuristicCandidateLimit = heuristicCandidateLimit;
    }

    public int getEmbeddingCandidateLimit() {
        return embeddingCandidateLimit;
    }

    public void setEmbeddingCandidateLimit(int embeddingCandidateLimit) {
        this.embeddingCandidateLimit = embeddingCandidateLimit;
    }

    public int getGlobalCandidateLimit() {
        return globalCandidateLimit;
    }

    public void setGlobalCandidateLimit(int globalCandidateLimit) {
        this.globalCandidateLimit = globalCandidateLimit;
    }

    public void validate() {
        requirePositive(chunkSize, "chunkSize");
        requirePositive(fetchSize, "fetchSize");
        requirePositive(graphCandidateLimit, "graphCandidateLimit");
        requirePositive(heuristicCandidateLimit, "heuristicCandidateLimit");
        requirePositive(embeddingCandidateLimit, "embeddingCandidateLimit");
        requirePositive(globalCandidateLimit, "globalCandidateLimit");
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalStateException(name + " must be positive");
        }
    }
}

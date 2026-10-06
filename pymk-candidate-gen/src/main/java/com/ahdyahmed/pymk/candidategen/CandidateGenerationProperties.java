package com.ahdyahmed.pymk.candidategen;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configurable per-source budgets, global cap, and fan-out concurrency. */
@Component
@ConfigurationProperties(prefix = "pymk.candidate-generation")
public class CandidateGenerationProperties {

    private int globalLimit = 3_000;
    private int maxConcurrency = 3;
    private final Map<CandidateSourceType, Integer> sourceLimits = defaultSourceLimits();

    public int getGlobalLimit() {
        return globalLimit;
    }

    public void setGlobalLimit(int globalLimit) {
        this.globalLimit = globalLimit;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public Map<CandidateSourceType, Integer> getSourceLimits() {
        return sourceLimits;
    }

    public void setSourceLimits(Map<CandidateSourceType, Integer> sourceLimits) {
        Map<CandidateSourceType, Integer> replacement =
                new EnumMap<>(CandidateSourceType.class);
        if (sourceLimits != null) {
            replacement.putAll(sourceLimits);
        }
        this.sourceLimits.clear();
        this.sourceLimits.putAll(replacement);
    }

    public int sourceLimit(CandidateSourceType sourceType) {
        Integer limit = sourceLimits.get(sourceType);
        if (limit == null || limit <= 0) {
            throw new IllegalStateException("A positive source limit is required for " + sourceType);
        }
        return limit;
    }

    public void validate() {
        if (globalLimit <= 0) {
            throw new IllegalStateException("globalLimit must be positive");
        }
        if (maxConcurrency <= 0) {
            throw new IllegalStateException("maxConcurrency must be positive");
        }
    }

    private static Map<CandidateSourceType, Integer> defaultSourceLimits() {
        Map<CandidateSourceType, Integer> limits = new EnumMap<>(CandidateSourceType.class);
        limits.put(CandidateSourceType.HEURISTIC, 1_000);
        limits.put(CandidateSourceType.GRAPH_WALK, 2_000);
        limits.put(CandidateSourceType.EMBEDDING, 1_000);
        return limits;
    }
}

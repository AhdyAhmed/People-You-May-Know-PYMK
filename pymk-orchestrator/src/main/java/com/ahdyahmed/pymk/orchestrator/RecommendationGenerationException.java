package com.ahdyahmed.pymk.orchestrator;

/** Signals that the recommendation pipeline could not produce a complete result. */
public class RecommendationGenerationException extends RuntimeException {

    public RecommendationGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}

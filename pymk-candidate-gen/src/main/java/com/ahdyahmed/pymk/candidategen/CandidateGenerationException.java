package com.ahdyahmed.pymk.candidategen;

/** Indicates that the L0 fan-out could not produce a complete candidate set. */
public class CandidateGenerationException extends RuntimeException {

    public CandidateGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}

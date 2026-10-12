package com.ahdyahmed.pymk.featurestore;

/** Raised when a requested pair did not exist at the feature snapshot. */
public class FeatureInputUnavailableException extends RuntimeException {

    public FeatureInputUnavailableException(String message) {
        super(message);
    }
}

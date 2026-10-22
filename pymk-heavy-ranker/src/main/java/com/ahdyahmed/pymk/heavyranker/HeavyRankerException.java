package com.ahdyahmed.pymk.heavyranker;

/** Fail-closed artifact, feature or inference failure; never a partial result. */
public class HeavyRankerException extends RuntimeException {
    public HeavyRankerException(String message) { super(message); }
    public HeavyRankerException(String message, Throwable cause) { super(message, cause); }
}

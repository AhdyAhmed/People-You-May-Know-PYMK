package com.ahdyahmed.pymk.lightranker;

/** Fail-closed signal for an invalid artifact or an unsafe serving request. */
public class LightRankerException extends RuntimeException {

    public LightRankerException(String message) {
        super(message);
    }

    public LightRankerException(String message, Throwable cause) {
        super(message, cause);
    }
}

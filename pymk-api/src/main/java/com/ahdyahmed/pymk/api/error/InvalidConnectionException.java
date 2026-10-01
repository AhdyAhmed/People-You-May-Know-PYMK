package com.ahdyahmed.pymk.api.error;

/** A semantically invalid connect request (e.g. a member connecting to themselves). Mapped to HTTP 400. */
public class InvalidConnectionException extends RuntimeException {

    public InvalidConnectionException(String message) {
        super(message);
    }
}

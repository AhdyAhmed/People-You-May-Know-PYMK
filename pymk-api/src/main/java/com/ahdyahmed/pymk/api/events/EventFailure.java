package com.ahdyahmed.pymk.api.events;

public class EventFailure extends RuntimeException {
    private final int status;
    public EventFailure(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}

package com.ahdyahmed.pymk.api.events;

/** Bounded, non-sensitive diagnostic categories; never include payloads in DLT errors. */
public class ConsumerEventFailure extends RuntimeException {
    public enum Reason { INVALID_CONTRACT, IDENTITY_CONFLICT, REPLAY_BEFORE_FLOOR }
    private final Reason reason;
    public ConsumerEventFailure(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}

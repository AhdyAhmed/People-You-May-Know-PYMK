package com.ahdyahmed.pymk.api.error;

/** Thrown when a request references a member ID that does not exist. Mapped to HTTP 404. */
public class MemberNotFoundException extends RuntimeException {

    private final long memberId;

    public MemberNotFoundException(long memberId) {
        super("Member not found: " + memberId);
        this.memberId = memberId;
    }

    public long getMemberId() {
        return memberId;
    }
}

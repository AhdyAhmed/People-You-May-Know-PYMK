package com.ahdyahmed.pymk.batch;

import java.time.Instant;
import java.time.format.DateTimeParseException;

final class JobParameterInstants {

    private JobParameterInstants() {
    }

    static Instant required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " job parameter is required");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    name + " must be an ISO-8601 instant, for example 2026-10-02T18:00:00Z",
                    exception);
        }
    }
}

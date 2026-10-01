package com.ahdyahmed.pymk.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Plain unit test: the unique-constraint race is impractical to trigger via MockMvc. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void uniqueConstraintRaceMapsTo409() {
        ProblemDetail problem = handler.handleDataIntegrity(
                new DataIntegrityViolationException("duplicate key value violates uq_connections_pair"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Conflict");
    }
}

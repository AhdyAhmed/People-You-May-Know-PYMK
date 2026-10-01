package com.ahdyahmed.pymk.api.error;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Central error mapping. Every error is an RFC 9457 {@link ProblemDetail}
 * ({@code application/problem+json}), so clients get one consistent shape.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} gives the standard Spring
 * MVC exceptions (type mismatch on a path variable, malformed JSON, ...) the
 * same format for free; only domain-specific exceptions are mapped here.</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String ERROR_TYPE_BASE = "https://github.com/AhdyAhmed/People-You-May-Know-PYMK/errors/";

    @ExceptionHandler(MemberNotFoundException.class)
    ProblemDetail handleMemberNotFound(MemberNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Member not found");
        problem.setType(URI.create(ERROR_TYPE_BASE + "member-not-found"));
        problem.setProperty("memberId", ex.getMemberId());
        return problem;
    }

    @ExceptionHandler(InvalidConnectionException.class)
    ProblemDetail handleInvalidConnection(InvalidConnectionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid connection request");
        problem.setType(URI.create(ERROR_TYPE_BASE + "invalid-connection"));
        return problem;
    }

    /**
     * Backstop for the {@code uq_connections_pair} unique constraint: two
     * concurrent requests passed the existence check and the loser's insert
     * failed. The edge exists, so the client may safely retry (idempotent).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The request conflicted with a concurrent change; retrying is safe.");
        problem.setTitle("Conflict");
        problem.setType(URI.create(ERROR_TYPE_BASE + "conflict"));
        return problem;
    }

    /** Adds a field -> message map to Bean Validation failures. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        ProblemDetail problem = ex.getBody();
        problem.setTitle("Validation failed");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
}

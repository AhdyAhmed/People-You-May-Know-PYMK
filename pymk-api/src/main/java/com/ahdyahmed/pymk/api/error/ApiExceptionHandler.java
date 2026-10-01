package com.ahdyahmed.pymk.api.error;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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

    @ExceptionHandler(MemberNotFoundException.class)
    ProblemDetail handleMemberNotFound(MemberNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Member not found");
        problem.setType(URI.create("https://github.com/AhdyAhmed/People-You-May-Know-PYMK/errors/member-not-found"));
        problem.setProperty("memberId", ex.getMemberId());
        return problem;
    }
}

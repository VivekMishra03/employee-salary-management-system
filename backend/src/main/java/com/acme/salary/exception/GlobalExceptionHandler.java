package com.acme.salary.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * requirements.md section 7 / CLAUDE.md section 7: every error is RFC 7807 {@code
 * application/problem+json} with a stable {@code code}, produced here and nowhere else. A raw stack
 * trace, framework error page or exception message never reaches a client.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so the framework's own exceptions (malformed
 * JSON, unsupported media type, method not allowed...) are also rendered as problem+json;
 * {@link #handleExceptionInternal} then adds the {@code code}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setTitle("Unauthorized");
        problem.setProperty("code", "INVALID_CREDENTIALS");
        return problem;
    }

    /**
     * Reports which fields failed and why, and deliberately never the rejected value: the field
     * might be a password, and problem responses are logged and cached by intermediaries (NFR-4).
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message",
                        fe.getDefaultMessage() == null ? "is invalid" : fe.getDefaultMessage()))
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Bad Request");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    /**
     * Gives every other framework-rendered problem a {@code code} derived from its HTTP status.
     *
     * <p>Some framework exceptions -- notably an unreadable request body -- reach here with a
     * <em>null</em> body and would otherwise produce an empty response with no content type, so a
     * problem body is built for them. Its detail is deliberately generic and never taken from the
     * exception: Jackson's parse-error message quotes part of the request, which may be a password.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        HttpStatus resolved = HttpStatus.resolve(statusCode.value());
        String code = resolved != null ? resolved.name() : "ERROR";

        Object responseBody = response.getBody();
        if (responseBody == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(statusCode,
                    statusCode.value() == HttpStatus.BAD_REQUEST.value()
                            ? "The request is malformed or could not be read"
                            : "The request could not be processed");
            if (resolved != null) {
                problem.setTitle(resolved.getReasonPhrase());
            }
            problem.setProperty("code", code);
            return ResponseEntity.status(statusCode).headers(response.getHeaders()).body(problem);
        }
        if (responseBody instanceof ProblemDetail problem) {
            // getProperties() is null, not empty, until something has been set on the problem.
            Map<String, Object> properties = problem.getProperties();
            if (properties == null || !properties.containsKey("code")) {
                problem.setProperty("code", code);
            }
        }
        return response;
    }

    /**
     * Last resort: log the real cause server-side, tell the client nothing about it.
     *
     * <p>Spring Security's own exceptions are re-thrown untouched. A catch-all {@code
     * @ExceptionHandler(Exception.class)} would otherwise intercept an {@code AccessDeniedException}
     * thrown from a controller before Spring Security's filter sees it, turning a 403 into a 500.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) throws Exception {
        if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
            throw e;
        }
        log.error("Unhandled exception", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred");
        problem.setTitle("Internal Server Error");
        problem.setProperty("code", "INTERNAL_ERROR");
        return problem;
    }
}

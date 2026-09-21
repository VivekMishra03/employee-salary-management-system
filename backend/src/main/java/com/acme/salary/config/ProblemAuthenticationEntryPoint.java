package com.acme.salary.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * FR-1.2/FR-1.3: what an unauthenticated request to a protected path receives. Spring Security's
 * default is an empty 401 (or a redirect to an HTML login page); the API contract (requirements.md
 * section 7) is RFC 7807 {@code application/problem+json} with a stable {@code code}, and RFC 6750
 * asks a 401 to carry {@code WWW-Authenticate: Bearer}.
 *
 * <p>The body is identical whether the token was missing, malformed, tampered with or expired: the
 * client's only possible remedy is to sign in again, and finer detail would only help an attacker.
 */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    public static final String CODE = "UNAUTHENTICATED";

    private final ObjectMapper objectMapper;

    public ProblemAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Authentication is required to access this resource");
        problem.setTitle("Unauthorized");
        problem.setProperty("code", CODE);

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}

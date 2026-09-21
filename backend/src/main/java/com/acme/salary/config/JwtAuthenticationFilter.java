package com.acme.salary.config;

import com.acme.salary.dto.AuthenticatedUser;
import com.acme.salary.exception.InvalidTokenException;
import com.acme.salary.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * FR-1.2: authenticates a request from its {@code Authorization: Bearer <jwt>} header.
 *
 * <p>The filter only ever *establishes* authentication; it never rejects. A missing, malformed,
 * wrongly-schemed, tampered or expired token simply leaves the request unauthenticated, and the
 * authorization rules plus {@link ProblemAuthenticationEntryPoint} turn that into a 401 on every
 * protected path. That keeps "who are you" and "may you enter" in separate places, and means a stale
 * token sent to a public endpoint (login) is ignored rather than breaking it.
 *
 * <p>Deliberately not a {@code @Component}: a filter bean is auto-registered in the servlet chain
 * as well as the security chain and would run outside the security rules. {@link SecurityConfig}
 * constructs it explicitly instead.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                JwtService.TokenClaims claims = jwtService.parse(header.substring(BEARER_PREFIX.length()).trim());
                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        new AuthenticatedUser(claims.userId(), claims.email()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (InvalidTokenException e) {
                // Leave the request unauthenticated. Not logged: the token is a credential (NFR-4).
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}

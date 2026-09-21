package com.acme.salary.service;

import com.acme.salary.config.JwtProperties;
import com.acme.salary.exception.InvalidTokenException;
import com.acme.salary.model.AppUserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

/**
 * FR-1.1 / FR-1.3: issues and validates the JWT bearer tokens.
 *
 * <p>Both signing and expiry checking use the injected {@link Clock}, never the wall clock (NFR-3),
 * so expiry is testable at exact instants. jjwt is told to use that same clock when parsing;
 * without that, it would validate {@code exp} against real time and a fixed-clock test could not
 * observe an expired token.
 *
 * <p>Tokens are HS256-signed. {@link Keys#hmacShaKeyFor} rejects keys under 256 bits, which is a
 * second line of defence behind {@link JwtProperties}' startup validation.
 */
@Service
public class JwtService {

    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final long expiryMinutes;
    private final Clock clock;
    private final JwtParser parser;

    public JwtService(JwtProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expiryMinutes = properties.expiryMinutes();
        this.clock = clock;
        this.parser = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    public IssuedToken issue(Long userId, String email, AppUserRole role) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(expiryMinutes * 60);
        String token = Jwts.builder()
                .subject(email)
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_ROLE, role.name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * @throws InvalidTokenException for anything other than a well-formed, correctly signed,
     *                               unexpired token carrying every claim this service issues
     */
    public TokenClaims parse(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("Token is missing");
        }
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            String email = claims.getSubject();
            Number userId = claims.get(CLAIM_USER_ID, Number.class);
            String role = claims.get(CLAIM_ROLE, String.class);
            if (email == null || userId == null || role == null) {
                throw new InvalidTokenException("Token is missing required claims");
            }
            return new TokenClaims(userId.longValue(), email, AppUserRole.valueOf(role));
        } catch (JwtException | IllegalArgumentException e) {
            // One exception type, and no detail about which check failed (see InvalidTokenException).
            throw new InvalidTokenException("Token is invalid or expired", e);
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public record TokenClaims(Long userId, String email, AppUserRole role) {
    }
}

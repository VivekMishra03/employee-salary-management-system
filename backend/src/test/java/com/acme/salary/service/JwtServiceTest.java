package com.acme.salary.service;

import com.acme.salary.config.JwtProperties;
import com.acme.salary.exception.InvalidTokenException;
import com.acme.salary.model.AppUserRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-1.1 / FR-1.3 / NFR-4: JWT issue and validation. Pure unit tests -- no Spring context, no
 * database -- with a fixed {@link Clock} so expiry is asserted at exact instants rather than
 * against the wall clock (NFR-3).
 */
class JwtServiceTest {

    private static final String SECRET = "test-only-secret-key-that-is-at-least-32-bytes-long!!";
    private static final Instant NOW = Instant.parse("2026-03-01T09:00:00Z");
    private static final long EXPIRY_MINUTES = 60;

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static JwtService serviceAt(Instant instant, String secret) {
        return new JwtService(new JwtProperties(secret, EXPIRY_MINUTES), clockAt(instant));
    }

    @Test
    @DisplayName("FR-1.1: an issued token parses back to the same user, email and role")
    void issue_thenParse_roundTripsTheClaims() {
        JwtService service = serviceAt(NOW, SECRET);

        JwtService.IssuedToken issued = service.issue(42L, "hr.manager@acme.example", AppUserRole.HR_MANAGER);
        JwtService.TokenClaims claims = service.parse(issued.token());

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.email()).isEqualTo("hr.manager@acme.example");
        assertThat(claims.role()).isEqualTo(AppUserRole.HR_MANAGER);
    }

    @Test
    @DisplayName("FR-1.3: a token expires exactly the configured number of minutes after issue")
    void issue_setsExpiryFromTheInjectedClock() {
        JwtService.IssuedToken issued = serviceAt(NOW, SECRET)
                .issue(1L, "hr.manager@acme.example", AppUserRole.HR_MANAGER);

        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(EXPIRY_MINUTES * 60));
    }

    @Test
    @DisplayName("FR-1.3: a token is still accepted one second before it expires")
    void parse_justBeforeExpiry_isAccepted() {
        String token = serviceAt(NOW, SECRET).issue(1L, "a@acme.example", AppUserRole.HR_MANAGER).token();

        JwtService later = serviceAt(NOW.plusSeconds(EXPIRY_MINUTES * 60 - 1), SECRET);

        assertThat(later.parse(token).userId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("FR-1.3: expiry is enforced server-side -- a token past its expiry is rejected")
    void parse_afterExpiry_isRejected() {
        String token = serviceAt(NOW, SECRET).issue(1L, "a@acme.example", AppUserRole.HR_MANAGER).token();

        JwtService later = serviceAt(NOW.plusSeconds(EXPIRY_MINUTES * 60 + 1), SECRET);

        assertThatThrownBy(() -> later.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("FR-1.2: a token whose payload was altered is rejected")
    void parse_withTamperedPayload_isRejected() {
        JwtService service = serviceAt(NOW, SECRET);
        String token = service.issue(1L, "a@acme.example", AppUserRole.HR_MANAGER).token();
        String[] parts = token.split("\\.");
        // Swap in a different (validly base64url-encoded) payload, keeping the original signature.
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"attacker@evil.example\",\"uid\":999,\"role\":\"HR_MANAGER\"}"
                        .getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> service.parse(forged)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("FR-1.2: a token signed with a different key is rejected")
    void parse_withTokenSignedByAnotherKey_isRejected() {
        String foreign = serviceAt(NOW, "another-secret-key-that-is-also-32-bytes-or-more!!")
                .issue(1L, "a@acme.example", AppUserRole.HR_MANAGER).token();

        assertThatThrownBy(() -> serviceAt(NOW, SECRET).parse(foreign))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("FR-1.2: an unsigned token (alg=none) is rejected")
    void parse_withUnsignedToken_isRejected() {
        String unsigned = Jwts.builder().subject("a@acme.example").claim("uid", 1).claim("role", "HR_MANAGER")
                .compact();

        assertThatThrownBy(() -> serviceAt(NOW, SECRET).parse(unsigned))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("FR-1.2: a token missing the required claims is rejected, not accepted with nulls")
    void parse_withValidSignatureButNoUserClaims_isRejected() {
        String bare = Jwts.builder().subject("a@acme.example").expiration(java.util.Date.from(NOW.plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();

        assertThatThrownBy(() -> serviceAt(NOW, SECRET).parse(bare)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("FR-1.2: malformed input is rejected with the same exception, not a raw parser error")
    void parse_withGarbage_isRejected() {
        JwtService service = serviceAt(NOW, SECRET);

        assertThatThrownBy(() -> service.parse("not.a.jwt")).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> service.parse("")).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> service.parse(null)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("NFR-4: a signing key shorter than 256 bits cannot be used")
    void constructor_withShortSecret_isRejected() {
        assertThatThrownBy(() -> serviceAt(NOW, "too-short"))
                .isInstanceOf(io.jsonwebtoken.security.WeakKeyException.class);
    }
}

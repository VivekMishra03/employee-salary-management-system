package com.acme.salary.service;

import com.acme.salary.config.JwtProperties;
import com.acme.salary.exception.InvalidCredentialsException;
import com.acme.salary.model.AppUser;
import com.acme.salary.model.AppUserRole;
import com.acme.salary.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FR-1.1: sign-in with email and password against a BCrypt-hashed credential. Unit test with a real
 * {@link BCryptPasswordEncoder} and {@link JwtService}; only the repository is stubbed, so
 * assertions are on returned values and thrown exceptions, not on mock interactions.
 */
class AuthServiceTest {

    private static final String SECRET = "test-only-secret-key-that-is-at-least-32-bytes-long!!";
    private static final Instant NOW = Instant.parse("2026-03-01T09:00:00Z");

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4); // low cost: fast tests
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JwtService jwtService = new JwtService(new JwtProperties(SECRET, 60), clock);
    private final AppUserRepository repository = mock(AppUserRepository.class);

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(repository, encoder, jwtService);
    }

    private AppUser userWithPassword(String rawPassword, boolean enabled) {
        AppUser user = new AppUser("hr.manager@acme.example", encoder.encode(rawPassword), "Priya Sharma",
                AppUserRole.HR_MANAGER, enabled);
        ReflectionTestUtils.setField(user, "id", 42L);
        return user;
    }

    @Test
    @DisplayName("FR-1.1: valid credentials yield a token for that user")
    void login_withValidCredentials_returnsATokenForThatUser() {
        when(repository.findByEmail("hr.manager@acme.example"))
                .thenReturn(Optional.of(userWithPassword("correct-horse", true)));

        JwtService.IssuedToken issued = authService.login("hr.manager@acme.example", "correct-horse");

        JwtService.TokenClaims claims = jwtService.parse(issued.token());
        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.email()).isEqualTo("hr.manager@acme.example");
        assertThat(claims.role()).isEqualTo(AppUserRole.HR_MANAGER);
    }

    @Test
    @DisplayName("FR-1.1: a wrong password is rejected")
    void login_withWrongPassword_isRejected() {
        when(repository.findByEmail("hr.manager@acme.example"))
                .thenReturn(Optional.of(userWithPassword("correct-horse", true)));

        assertThatThrownBy(() -> authService.login("hr.manager@acme.example", "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("FR-1.1: an unknown email is rejected with the same exception as a wrong password")
    void login_withUnknownEmail_isIndistinguishableFromAWrongPassword() {
        when(repository.findByEmail("nobody@acme.example")).thenReturn(Optional.empty());
        when(repository.findByEmail("hr.manager@acme.example"))
                .thenReturn(Optional.of(userWithPassword("correct-horse", true)));

        Throwable unknownEmail = org.assertj.core.api.Assertions.catchThrowable(
                () -> authService.login("nobody@acme.example", "whatever"));
        Throwable wrongPassword = org.assertj.core.api.Assertions.catchThrowable(
                () -> authService.login("hr.manager@acme.example", "wrong"));

        // Same type and same message: a caller must not be able to probe which emails exist.
        assertThat(unknownEmail).isInstanceOf(InvalidCredentialsException.class);
        assertThat(wrongPassword).isInstanceOf(InvalidCredentialsException.class);
        assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
    }

    @Test
    @DisplayName("FR-1.1: a disabled account is rejected even with the correct password")
    void login_withDisabledAccount_isRejected() {
        when(repository.findByEmail("hr.manager@acme.example"))
                .thenReturn(Optional.of(userWithPassword("correct-horse", false)));

        assertThatThrownBy(() -> authService.login("hr.manager@acme.example", "correct-horse"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("FR-1.1: the stored credential is a BCrypt hash, never the plaintext")
    void encoder_producesABcryptHashThatIsNotThePlaintext() {
        String hash = encoder.encode("correct-horse");

        assertThat(hash).startsWith("$2").isNotEqualTo("correct-horse");
        assertThat(encoder.matches("correct-horse", hash)).isTrue();
    }
}

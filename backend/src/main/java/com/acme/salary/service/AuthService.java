package com.acme.salary.service;

import com.acme.salary.exception.InvalidCredentialsException;
import com.acme.salary.model.AppUser;
import com.acme.salary.repository.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-1.1: signs an HR Manager in with email and password.
 *
 * <p>Every failure -- unknown email, wrong password, disabled account -- raises the same {@link
 * InvalidCredentialsException}, so the response cannot be used to discover which emails have
 * accounts. To keep the *timing* from leaking the same thing, an unknown email still performs a
 * BCrypt comparison (against a hash computed once at construction); otherwise "no such user"
 * returns in microseconds while "wrong password" takes a full BCrypt round.
 *
 * <p>Neither the password nor the token is ever logged (NFR-4).
 */
@Service
public class AuthService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final String timingEqualisationHash;

    public AuthService(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.timingEqualisationHash = passwordEncoder.encode("timing-equalisation-not-a-real-credential");
    }

    @Transactional(readOnly = true)
    public JwtService.IssuedToken login(String email, String rawPassword) {
        AppUser user = appUserRepository.findByEmail(email).orElse(null);
        if (user == null) {
            passwordEncoder.matches(rawPassword, timingEqualisationHash);
            throw new InvalidCredentialsException();
        }
        boolean passwordMatches = passwordEncoder.matches(rawPassword, user.getPasswordHash());
        if (!passwordMatches || !user.isEnabled()) {
            throw new InvalidCredentialsException();
        }
        return jwtService.issue(user.getId(), user.getEmail(), user.getRole());
    }
}

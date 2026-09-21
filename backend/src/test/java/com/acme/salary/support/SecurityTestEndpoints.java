package com.acme.salary.support;

import com.acme.salary.dto.AuthenticatedUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints, imported explicitly by the tests that need them (never component-scanned
 * into the application). They exist because M2 has no real protected endpoint yet: FR-1.2 is
 * about *every* endpoint, so the tests need something behind the security chain to hit.
 */
@RestController
@RequestMapping("/api/v1/test")
public class SecurityTestEndpoints {

    @GetMapping("/whoami")
    public String whoami(@AuthenticationPrincipal AuthenticatedUser user) {
        return user.userId() + ":" + user.email();
    }

    @GetMapping("/boom")
    public String boom() {
        throw new IllegalStateException("boom-secret-detail");
    }

    @GetMapping("/forbidden")
    public String forbidden() {
        throw new AccessDeniedException("nope");
    }
}

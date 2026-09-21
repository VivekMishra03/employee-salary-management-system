package com.acme.salary.config;

import com.acme.salary.model.AppUserRole;
import com.acme.salary.service.JwtService;
import com.acme.salary.support.SecurityTestEndpoints;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-1.2 / FR-1.3: every endpoint except login and health requires a valid, unexpired JWT bearer
 * token, and a rejection is a 401 problem+json rather than an HTML login page or an empty body.
 */
@SpringBootTest(properties = "PORT=8080")
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Import(SecurityTestEndpoints.class)
class JwtAuthenticationTest {

    private static final String WHOAMI = "/api/v1/test/whoami";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JwtProperties jwtProperties;

    private String validToken() {
        return jwtService.issue(42L, "hr.manager@acme.example", AppUserRole.HR_MANAGER).token();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    @DisplayName("FR-1.2: no token is a 401 problem+json with WWW-Authenticate: Bearer")
    void protectedEndpoint_withoutToken_returns401ProblemJson() throws Exception {
        mockMvc.perform(get(WHOAMI))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("FR-1.2: a valid token reaches the controller, which sees the authenticated user")
    void protectedEndpoint_withValidToken_reachesTheControllerWithThePrincipal() throws Exception {
        mockMvc.perform(get(WHOAMI).header("Authorization", bearer(validToken())))
                .andExpect(status().isOk())
                .andExpect(content().string("42:hr.manager@acme.example"));
    }

    @Test
    @DisplayName("FR-1.3: an expired token is a 401 even though its signature is genuine")
    void protectedEndpoint_withExpiredToken_returns401() throws Exception {
        // Same secret, but issued by a clock set years in the past, so the app's real clock sees it
        // as long expired.
        JwtService past = new JwtService(jwtProperties, Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC));
        String expired = past.issue(42L, "hr.manager@acme.example", AppUserRole.HR_MANAGER).token();

        mockMvc.perform(get(WHOAMI).header("Authorization", bearer(expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("FR-1.2: a token with a tampered signature is a 401")
    void protectedEndpoint_withTamperedToken_returns401() throws Exception {
        String token = validToken();
        String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");

        mockMvc.perform(get(WHOAMI).header("Authorization", bearer(tampered)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FR-1.2: garbage after 'Bearer' is a 401")
    void protectedEndpoint_withGarbageToken_returns401() throws Exception {
        mockMvc.perform(get(WHOAMI).header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FR-1.2: any other auth scheme is a 401, even with a valid token as the credential")
    void protectedEndpoint_withNonBearerScheme_returns401() throws Exception {
        mockMvc.perform(get(WHOAMI).header("Authorization", "Basic " + validToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FR-1.2: the token is not accepted from a query parameter")
    void protectedEndpoint_withTokenInQueryString_returns401() throws Exception {
        mockMvc.perform(get(WHOAMI).param("access_token", validToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FR-1.2: security is separate from routing -- a valid token on an unmapped path is a 404, not a 401")
    void unmappedPath_withValidToken_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist").header("Authorization", bearer(validToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FR-1.2: an unmapped path without a token is a 401, so the API does not reveal which paths exist")
    void unmappedPath_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("requirements.md 7: an unexpected exception is a 500 problem+json that leaks nothing")
    void unexpectedException_returns500ProblemJsonWithoutTheMessage() throws Exception {
        mockMvc.perform(get("/api/v1/test/boom").header("Authorization", bearer(validToken())))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("boom-secret-detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    @DisplayName("an AccessDeniedException is left to Spring Security (403), not swallowed as a 500")
    void accessDenied_isNotConvertedIntoAServerError() throws Exception {
        mockMvc.perform(get("/api/v1/test/forbidden").header("Authorization", bearer(validToken())))
                .andExpect(status().isForbidden());
    }
}

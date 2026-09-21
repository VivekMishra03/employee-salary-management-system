package com.acme.salary.controller;

import com.acme.salary.model.AppUser;
import com.acme.salary.model.AppUserRole;
import com.acme.salary.repository.AppUserRepository;
import com.acme.salary.service.JwtService;
import com.jayway.jsonpath.JsonPath;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-1.1 over HTTP: POST /api/v1/auth/login. Errors are RFC 7807 problem+json with a stable
 * {@code code} field (requirements.md section 7).
 */
@SpringBootTest(properties = "PORT=8080")
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Transactional
class AuthControllerTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void seedHrManager() {
        appUserRepository.saveAndFlush(new AppUser("hr.manager@acme.example",
                passwordEncoder.encode("correct-horse"), "Priya Sharma", AppUserRole.HR_MANAGER, true));
    }

    private static String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    @DisplayName("FR-1.1: valid credentials return a bearer token for that user")
    void login_withValidCredentials_returnsABearerToken() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("hr.manager@acme.example", "correct-horse")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String token = JsonPath.read(body, "$.accessToken");
        assertThat(jwtService.parse(token).email()).isEqualTo("hr.manager@acme.example");
    }

    @Test
    @DisplayName("FR-1.1: a wrong password is a 401 problem+json with a stable code")
    void login_withWrongPassword_returns401ProblemJson() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("hr.manager@acme.example", "wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                // Nothing the caller sent may be echoed back.
                .andExpect(content().string(not(containsString("wrong"))))
                .andExpect(content().string(not(containsString("hr.manager@acme.example"))));
    }

    @Test
    @DisplayName("FR-1.1: an unknown email produces a response byte-identical to a wrong password")
    void login_withUnknownEmail_isIndistinguishableFromAWrongPassword() throws Exception {
        String unknown = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("nobody@acme.example", "whatever")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String wrongPassword = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("hr.manager@acme.example", "wrong")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknown).isEqualTo(wrongPassword);
    }

    @Test
    @DisplayName("NFR-4: blank fields are a 400 that names the fields but never echoes a value")
    void login_withBlankFields_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("", "")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='password')]").exists());
    }

    @Test
    @DisplayName("NFR-4: an oversized password is rejected and not echoed back")
    void login_withOversizedPassword_isRejectedWithoutEchoingIt() throws Exception {
        String huge = "x".repeat(300);

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("hr.manager@acme.example", huge)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(content().string(not(containsString("xxxxxxxxxx"))));
    }

    @Test
    @DisplayName("a malformed JSON body is a 400 problem+json, not a raw framework error")
    void login_withMalformedJson_returns400ProblemJson() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}

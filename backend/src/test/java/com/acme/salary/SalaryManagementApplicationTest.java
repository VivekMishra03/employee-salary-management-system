package com.acme.salary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M0 foundation smoke tests.
 *
 * <p>Covers the M0 exit criterion in {@code requirements.md} section 10: the backend builds and
 * starts. Every later test depends on this being true, so it fails loudly and first when the
 * wiring breaks.
 *
 * <p>{@code PORT} is pinned explicitly so the suite cannot inherit the ambient value from whichever
 * shell runs it. Without this, exporting {@code PORT=not-a-number} fails three of the four tests in
 * this module — a result that depends on the developer's environment is the non-determinism NFR-3
 * forbids.
 */
@SpringBootTest(properties = "PORT=8080")
@AutoConfigureMockMvc
class SalaryManagementApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("M0 exit criterion: the Spring application context loads")
    void applicationContext_loads() {
        // No assertThat(context).isNotNull() here: if the context failed to load, this test would
        // already have aborted during injection, so such an assertion can never fail. The bean
        // lookup below is the assertion with teeth -- it fails if the entry point stops being a
        // @SpringBootApplication.
        assertThat(context.getBean(SalaryManagementApplication.class)).isNotNull();
    }

    @Test
    @DisplayName("M0: /actuator/health responds 200 and reports UP")
    void actuatorHealth_respondsUp() throws Exception {
        // requirements.md:271 names /actuator/health as one of only two routes that stay
        // unauthenticated. Asserting it now makes this the regression guard for when Spring
        // Security locks everything else down in M2 (FR-1.2).
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("M0: no endpoint beyond health is exposed")
    void actuator_exposesHealthOnly() throws Exception {
        // Keeps the unauthenticated surface minimal for NFR-4. /actuator/info is not in the
        // spec (requirements.md:271), so it must stay off until a requirement asks for it.
        mockMvc.perform(get("/actuator/info")).andExpect(status().isNotFound());
    }
}

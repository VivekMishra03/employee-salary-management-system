package com.acme.salary;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-6: the application must start from environment variables alone, with no code change between
 * local and production. Render injects {@code PORT} at runtime, so {@code server.port} must resolve
 * from a property spelled exactly {@code PORT}.
 *
 * <p><strong>What this test proves, precisely:</strong> that {@code application.yml} reads the key
 * {@code PORT} and not some other name. Renaming the placeholder to {@code ${HTTP_PORT:8080}} makes
 * it fail. That is the part we can get wrong and Render cannot tell us about until deploy.
 *
 * <p><strong>What it deliberately does not prove:</strong> that Spring's OS-environment property
 * source works. The inlined {@code properties} below shadow {@code SystemEnvironmentPropertySource},
 * so this test would still pass if that source were absent. Binding an OS environment variable is
 * framework behaviour, not ours, and the only ways to exercise it here are to mutate the JVM's
 * environment or bind a real socket — both non-deterministic, which NFR-3 forbids. The end-to-end
 * proof that Render's PORT injection works is the M9 deploy returning 200, not a unit test.
 */
@SpringBootTest(properties = "PORT=9999")
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
class PortConfigurationTest {

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("NFR-6: server.port resolves from a property named exactly PORT")
    void serverPort_resolvesFromThePortProperty() {
        assertThat(environment.getProperty("server.port")).isEqualTo("9999");
    }
}

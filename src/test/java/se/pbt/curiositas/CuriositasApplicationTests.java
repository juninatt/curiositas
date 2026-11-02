package se.pbt.curiositas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the full application starts against a real database, catching wiring and
 * configuration errors early.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "curiositas.admin.password=test-password")
class CuriositasApplicationTests {

    @Autowired
    private Environment environment;

    /** Fails if the Spring context cannot start. */
    @Test
    void contextLoads() {
    }

    /**
     * Checks that the settings in application.yaml take effect. A setting indented under the
     * wrong parent is silently ignored, and the application still starts without it.
     */
    @Test
    void appliesConfiguration() {
        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("curiositas.release"))
                .as("filled in from pom.xml by the build")
                .matches("\\d+\\.\\d+\\.\\d+.*");
    }
}

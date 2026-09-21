package com.coobi.logistics.streamprocessor.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * MVP-2.3 and MVP-3.2: the documented variables, not only the property keys, configure the
 * thresholds.
 *
 * <p>The shipped {@code application.yml} is read here, so the test fails when a
 * placeholder is renamed or dropped.
 */
class ProcessingPropertiesEnvironmentTest {

    @Test
    void readsTheSpeedLimitFromTheDocumentedEnvironmentVariable() throws IOException {
        ProcessingProperties properties = bind(Map.of("SPEED_LIMIT", "90"));

        assertThat(properties.getSpeedLimitKph()).isEqualTo(90.0);
    }

    @Test
    void readsTheStoppedThresholdsFromTheDocumentedEnvironmentVariables() throws IOException {
        ProcessingProperties properties = bind(Map.of(
                "STOPPED_WINDOW_SECONDS", "60",
                "MOVEMENT_THRESHOLD_METERS", "200"));

        assertThat(properties.getStoppedWindowSeconds()).isEqualTo(60L);
        assertThat(properties.getMovementThresholdMeters()).isEqualTo(200.0);
    }

    private static ProcessingProperties bind(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigurationPropertySources.attach(environment);
        environment.getPropertySources().addLast(applicationYaml());
        // A property source that stands in for the process environment, so the test does
        // not depend on how the machine running it is configured.
        environment
                .getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("documented", variables));

        return Binder.get(environment)
                .bind("coobi.processing", ProcessingProperties.class)
                .orElseThrow(() -> new IllegalStateException("coobi.processing did not bind"));
    }

    private static PropertySource<?> applicationYaml() throws IOException {
        return new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);
    }
}

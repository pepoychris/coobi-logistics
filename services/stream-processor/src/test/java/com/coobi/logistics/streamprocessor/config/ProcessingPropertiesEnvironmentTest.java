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
 * MVP-2.3: the documented variable, not only the property key, configures the threshold.
 *
 * <p>The shipped {@code application.yml} is read here, so the test fails when the
 * {@code SPEED_LIMIT} placeholder is renamed or dropped.
 */
class ProcessingPropertiesEnvironmentTest {

    @Test
    void readsTheSpeedLimitFromTheDocumentedEnvironmentVariable() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigurationPropertySources.attach(environment);
        environment.getPropertySources().addLast(applicationYaml());
        // A property source that stands in for the process environment, so the test does
        // not depend on how the machine running it is configured.
        environment
                .getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("documented", Map.of("SPEED_LIMIT", "90")));

        ProcessingProperties properties = Binder.get(environment)
                .bind("coobi.processing", ProcessingProperties.class)
                .orElseThrow(() -> new IllegalStateException("coobi.processing did not bind"));

        assertThat(properties.getSpeedLimitKph()).isEqualTo(90.0);
    }

    private static PropertySource<?> applicationYaml() throws IOException {
        return new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);
    }
}

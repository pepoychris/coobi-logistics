package com.coobi.logistics.streamprocessor.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * MVP-4.1: the database credentials come from the environment.
 *
 * <p>It reads the shipped {@code application.yml}, so the placeholders that make
 * {@code .env} the single source of truth for the Compose stack and this service are part of
 * the test rather than of the documentation only.
 */
class DataSourcePropertiesEnvironmentTest {

    @Test
    void defaultsMatchTheLocalComposeStack() throws IOException {
        DataSourceProperties properties = bind(Map.of());

        assertThat(properties.getUrl()).isEqualTo("jdbc:postgresql://localhost:5432/logistics");
        assertThat(properties.getUsername()).isEqualTo("logistics");
        assertThat(properties.getPassword()).isEmpty();
    }

    @Test
    void readsTheDatabaseFromTheDocumentedPostgresVariables() throws IOException {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("POSTGRES_DB", "coobi");
        variables.put("POSTGRES_USER", "coobi_app");
        variables.put("POSTGRES_PASSWORD", "local-secret");

        DataSourceProperties properties = bind(variables);

        assertThat(properties.getUrl()).isEqualTo("jdbc:postgresql://localhost:5432/coobi");
        assertThat(properties.getUsername()).isEqualTo("coobi_app");
        assertThat(properties.getPassword()).isEqualTo("local-secret");
    }

    @Test
    void letsTheStandardSpringVariablesOverrideThem() throws IOException {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("POSTGRES_DB", "coobi");
        variables.put("POSTGRES_USER", "coobi_app");
        variables.put("POSTGRES_PASSWORD", "local-secret");
        variables.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://db.internal:6432/coobi");
        variables.put("SPRING_DATASOURCE_USERNAME", "reporter");
        variables.put("SPRING_DATASOURCE_PASSWORD", "read-only");

        DataSourceProperties properties = bind(variables);

        assertThat(properties.getUrl()).isEqualTo("jdbc:postgresql://db.internal:6432/coobi");
        assertThat(properties.getUsername()).isEqualTo("reporter");
        assertThat(properties.getPassword()).isEqualTo("read-only");
    }

    private static DataSourceProperties bind(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigurationPropertySources.attach(environment);
        environment.getPropertySources().addLast(applicationYaml());
        // A property source that stands in for the process environment, so the test does not
        // depend on how the machine running it is configured.
        environment
                .getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("documented", variables));

        return Binder.get(environment)
                .bind("spring.datasource", DataSourceProperties.class)
                .orElseThrow(() -> new IllegalStateException("spring.datasource did not bind"));
    }

    private static PropertySource<?> applicationYaml() throws IOException {
        return new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);
    }
}

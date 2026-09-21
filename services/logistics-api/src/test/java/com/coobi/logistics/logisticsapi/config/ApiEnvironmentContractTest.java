package com.coobi.logistics.logisticsapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * The configuration contract of the service, asserted against the shipped
 * {@code application.yml}.
 *
 * <p>It reads that file rather than a copy of it, so the placeholders that make {@code .env}
 * the single source of truth for the Compose stack and this service are part of the test
 * instead of the documentation only - and so the decisions this service depends on are
 * pinned: the API reads the schema, it never creates or migrates it, and it runs on its own
 * port beside the other services.
 */
class ApiEnvironmentContractTest {

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

    @Test
    void leavesTheSchemaToTheStreamProcessorAndNeverWrites() throws IOException {
        Binder binder = binder(Map.of());

        assertThat(bind(binder, "spring.jpa.hibernate.ddl-auto", String.class))
                .as("no ORM creates or updates the schema this service does not own")
                .isEqualTo("none");
        assertThat(bind(binder, "spring.datasource.hikari.read-only", Boolean.class))
                .as("the API is a reader")
                .isTrue();
        assertThat(applicationYaml())
                .as("no migration engine is configured for a schema another service owns")
                .doesNotContain("flyway");
    }

    @Test
    void servesOnItsOwnPort() throws IOException {
        assertThat(bind(binder(Map.of()), "server.port", Integer.class)).isEqualTo(8082);
    }

    @Test
    void readsTheStatisticsSourcesFromTheDocumentedVariables() throws IOException {
        Binder binder = binder(Map.of("STREAM_PROCESSOR_METRICS_URL", "http://processor:8081/actuator/metrics"));

        assertThat(bind(binder, "coobi.statistics.stream-processor.metrics-url", String.class))
                .isEqualTo("http://processor:8081/actuator/metrics");
        assertThat(bind(binder, "coobi.statistics.stream-processor.processed-events-metric", String.class))
                .isEqualTo("logistics_events_processed_total");
        assertThat(bind(binder, "coobi.statistics.stream-processor.timeout", String.class)).isEqualTo("2s");

        // The placeholders are asserted on the file itself: the binder resolves them, so the
        // bound values say nothing about where the values come from.
        assertThat(applicationYaml()).contains("${STREAM_PROCESSOR_METRICS_URL:")
                .contains("${SPRING_DATASOURCE_URL:")
                .contains("${POSTGRES_DB:logistics}")
                .contains("${POSTGRES_USER:logistics}")
                .contains("${POSTGRES_PASSWORD:");
    }

    @Test
    void readsTheStreamSettingsFromTheDocumentedVariables() throws IOException {
        Binder binder = binder(Map.of(
                "COOBI_STREAM_EVENTS_POLL_INTERVAL", "250ms",
                "COOBI_STREAM_EVENTS_MAX_ALERTS_PER_POLL", "5",
                "COOBI_STREAM_EVENTS_MAX_VEHICLES_PER_POLL", "6",
                "COOBI_STREAM_STATISTICS_INTERVAL", "2s",
                "COOBI_STREAM_CLIENT_MAX_SUBSCRIBERS", "8"));

        assertThat(bind(binder, "coobi.stream.events.poll-interval", String.class)).isEqualTo("250ms");
        assertThat(bind(binder, "coobi.stream.events.max-alerts-per-poll", Integer.class)).isEqualTo(5);
        assertThat(bind(binder, "coobi.stream.events.max-vehicles-per-poll", Integer.class)).isEqualTo(6);
        assertThat(bind(binder, "coobi.stream.statistics.interval", String.class)).isEqualTo("2s");
        assertThat(bind(binder, "coobi.stream.client.max-subscribers", Integer.class)).isEqualTo(8);

        // The numbers that bound one browser rather than the whole stack are documented in the
        // service documentation and overridable the same way, without an entry of their own.
        assertThat(bind(binder(Map.of()), "coobi.stream.client.buffer-size", Integer.class)).isEqualTo(256);
        assertThat(bind(binder(Map.of()), "coobi.stream.client.heartbeat-interval", String.class)).isEqualTo("15s");
        assertThat(applicationYaml())
                .contains("${COOBI_STREAM_STATISTICS_INTERVAL:")
                .contains("${COOBI_STREAM_EVENTS_POLL_INTERVAL:")
                .contains("${COOBI_STREAM_CLIENT_MAX_SUBSCRIBERS:");
    }

    private static <T> T bind(Binder binder, String key, Class<T> type) {
        return binder.bind(key, type)
                .orElseThrow(() -> new IllegalStateException(key + " did not bind"));
    }

    private static DataSourceProperties bind(Map<String, Object> variables) throws IOException {
        return Binder.get(environment(variables))
                .bind("spring.datasource", DataSourceProperties.class)
                .orElseThrow(() -> new IllegalStateException("spring.datasource did not bind"));
    }

    private static Binder binder(Map<String, Object> variables) throws IOException {
        return Binder.get(environment(variables));
    }

    private static StandardEnvironment environment(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigurationPropertySources.attach(environment);
        environment.getPropertySources().addLast(applicationYamlSource());
        // A property source that stands in for the process environment, so the test does not
        // depend on how the machine running it is configured.
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("documented", variables));
        return environment;
    }

    private static PropertySource<?> applicationYamlSource() throws IOException {
        return new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);
    }

    private static String applicationYaml() throws IOException {
        return new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);
    }
}

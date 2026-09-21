package com.coobi.logistics.streamprocessor.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * MVP-4.1: PostgreSQL is wired through configuration and the schema belongs to the
 * migrations.
 *
 * <p>Nothing here restates the schema; the test asserts the properties and the constraints
 * the milestone names, so a migration that loses {@code UNIQUE} - the backstop of both
 * idempotent writes - or a configuration that lets an ORM create the schema fails.
 */
class PersistenceMigrationTest {

    @Test
    void createsBothVehicleTablesThroughAMigration() throws IOException {
        String migration = migration("db/migration/V1__create_vehicle_tables.sql");

        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS vehicles");
        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS vehicle_latest_state");
        assertThat(migration).containsIgnoringCase("UNIQUE (vehicle_id)");
        assertThat(migration).containsIgnoringCase("PRIMARY KEY");
        assertThat(migration).containsIgnoringCase("CREATE INDEX IF NOT EXISTS");
    }

    @Test
    void makesTheAlertEventIdUniqueInAMigration() throws IOException {
        String migration = migration("db/migration/V2__create_alerts_table.sql");

        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS alerts");
        assertThat(migration).containsIgnoringCase("UNIQUE (event_id)");
        assertThat(migration).containsIgnoringCase("JSONB");
        assertThat(migration).containsIgnoringCase("CREATE INDEX IF NOT EXISTS");
    }

    @Test
    void configuresTheDatabaseFromTheEnvironmentAndLeavesTheSchemaToFlyway() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigurationPropertySources.attach(environment);
        environment.getPropertySources().addLast(applicationYaml());

        Binder binder = Binder.get(environment);
        assertThat(bind(binder, "spring.flyway.enabled", Boolean.class)).isTrue();
        assertThat(bind(binder, "spring.jpa.hibernate.ddl-auto", String.class))
                .as("no ORM creates or updates the schema")
                .isEqualTo("none");
        assertThat(bind(binder, "spring.datasource.url", String.class)).startsWith("jdbc:postgresql://");

        // The placeholders are asserted on the file itself: the binder resolves them, so the
        // bound values say nothing about where the credentials come from.
        String yaml = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);
        assertThat(yaml).contains("${SPRING_DATASOURCE_URL:").contains("${POSTGRES_DB:logistics}");
        assertThat(yaml).contains("${SPRING_DATASOURCE_USERNAME:").contains("${POSTGRES_USER:logistics}");
        assertThat(yaml).contains("${SPRING_DATASOURCE_PASSWORD:").contains("${POSTGRES_PASSWORD:");
        assertThat(yaml).contains("locations: classpath:db/migration");
    }

    private static <T> T bind(Binder binder, String key, Class<T> type) {
        return binder.bind(key, type)
                .orElseThrow(() -> new IllegalStateException(key + " did not bind"));
    }

    private static String migration(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private static PropertySource<?> applicationYaml() throws IOException {
        return new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);
    }
}

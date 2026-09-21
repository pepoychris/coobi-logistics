package com.coobi.logistics.streamprocessor.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coobi.logistics.streamprocessor.event.AlertData;
import com.coobi.logistics.streamprocessor.event.AlertEvent;
import com.coobi.logistics.streamprocessor.event.AlertType;
import com.coobi.logistics.streamprocessor.persistence.JdbcTelemetryPersistence;
import com.coobi.logistics.streamprocessor.processing.VehicleState;
import com.coobi.logistics.streamprocessor.processing.VehicleStatus;
import com.coobi.logistics.streamprocessor.support.TelemetryFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * MVP-9.3: the shipped migrations and the production SQL against a real PostgreSQL.
 *
 * <p>The unit suite already runs this pair against an in-memory database in PostgreSQL mode,
 * which is where the SQL can be developed without a server. This test closes the remaining
 * gap: it runs the same migration files through Flyway - the engine of the service, in the
 * order the service runs them - against the PostgreSQL image of {@code compose.yml}, and then
 * drives the real {@link JdbcTelemetryPersistence} over the result. What it verifies is
 * exactly what a reader of the milestone needs to know:
 *
 * <pre>
 * vehicle state persistence   the vehicle row and its single latest-state row
 * alert persistence           the row, its type, its severity and its JSON document
 * idempotency                 a duplicate eventId never becomes a second alert
 * database migrations         applied by Flyway, recorded in its schema history
 * </pre>
 *
 * <p>No Spring context is needed: the subject is the migration engine and the SQL, and every
 * write of this test is synchronous, so the assertions need no polling. The class is skipped
 * with the reason Testcontainers reports when no Docker daemon is available.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresPersistenceIT {

    private static final String TRUCK = "TRUCK-92001";
    private static final String OTHER_TRUCK = "TRUCK-92002";
    private static final Instant EVENT_AT = Instant.parse("2026-09-21T11:00:00Z");
    private static final Instant WRITTEN_AT = Instant.parse("2026-09-21T11:05:00Z");
    private static final ObjectMapper OBJECT_MAPPER = TelemetryFixtures.objectMapper();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18.6"));

    private static Flyway flyway;
    private static JdbcTemplate jdbcTemplate;
    private static JdbcTelemetryPersistence persistence;

    @BeforeAll
    static void migrateTheContainerAndWireTheProductionPersistence() {
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        DataSource dataSource = DataSourceBuilder.create()
                .url(POSTGRES.getJdbcUrl())
                .username(POSTGRES.getUsername())
                .password(POSTGRES.getPassword())
                .build();
        jdbcTemplate = new JdbcTemplate(dataSource);
        persistence = new JdbcTelemetryPersistence(
                jdbcTemplate,
                new DataSourceTransactionManager(dataSource),
                OBJECT_MAPPER,
                Clock.fixed(WRITTEN_AT, ZoneOffset.UTC));
    }

    @Test
    void appliesTheShippedMigrationsToPostgresAndRecordsThem() {
        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getVersion().getVersion())
                .containsExactly("1", "2");
        assertThat(flyway.info().current().getVersion().getVersion())
                .as("the schema of the database is the latest migration of the service")
                .isEqualTo("2");

        assertThat(existingTables())
                .as("the migrations are the only definition of the schema")
                .contains("vehicles", "vehicle_latest_state", "alerts", "flyway_schema_history");
    }

    @Test
    void createsTheUniqueConstraintsTheIdempotentWritesRelyOn() {
        assertThat(uniqueConstraintsOf("vehicles")).contains("vehicles_vehicle_id_key");
        assertThat(uniqueConstraintsOf("alerts")).contains("alerts_event_id_key");
        assertThat(primaryKeyOf("vehicle_latest_state")).isEqualTo("vehicle_latest_state_pkey");
    }

    @Test
    void createsTheVehicleAndItsLatestStateOnTheFirstTelemetry() {
        String vehicleId = TRUCK + "-state";

        persistence.recordVehicleState(
                vehicleId, state(39.4699, -0.3763, 80.0, VehicleStatus.MOVING, EVENT_AT));

        assertThat(countOf("vehicles", "vehicle_id", vehicleId)).isEqualTo(1);
        assertThat(countOf("vehicle_latest_state", "vehicle_id", vehicleId)).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM vehicle_latest_state WHERE vehicle_id = ?", vehicleId);
        assertThat(row.get("latitude")).isEqualTo(39.4699);
        assertThat(row.get("longitude")).isEqualTo(-0.3763);
        assertThat(row.get("speed")).isEqualTo(80.0);
        assertThat(row.get("heading")).isEqualTo(TelemetryFixtures.DEMO_HEADING);
        assertThat(row.get("status")).isEqualTo("MOVING");
        assertThat(instantOf(row.get("last_update"))).isEqualTo(EVENT_AT);
        assertThat(instantOf(row.get("updated_at"))).isEqualTo(WRITTEN_AT);
    }

    @Test
    void overwritesTheLatestStateInsteadOfAppendingHistory() {
        String vehicleId = TRUCK + "-overwrite";

        persistence.recordVehicleState(
                vehicleId, state(39.4699, -0.3763, 80.0, VehicleStatus.MOVING, EVENT_AT));
        persistence.recordVehicleState(
                vehicleId, state(39.47026, -0.3763, 0.0, VehicleStatus.STOPPED, EVENT_AT.plusSeconds(300)));

        assertThat(countOf("vehicles", "vehicle_id", vehicleId))
                .as("the vehicle is created once")
                .isEqualTo(1);
        assertThat(countOf("vehicle_latest_state", "vehicle_id", vehicleId))
                .as("one state row per vehicle")
                .isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM vehicle_latest_state WHERE vehicle_id = ?", vehicleId);
        assertThat(row.get("speed")).isEqualTo(0.0);
        assertThat(row.get("status")).isEqualTo("STOPPED");
        assertThat(instantOf(row.get("last_update"))).isEqualTo(EVENT_AT.plusSeconds(300));
        assertThat(instantOf(jdbcTemplate.queryForObject(
                        "SELECT created_at FROM vehicles WHERE vehicle_id = ?", Object.class, vehicleId)))
                .as("the vehicle was first seen at the first write, not at the latest one")
                .isEqualTo(WRITTEN_AT);
    }

    @Test
    void storesTheAlertWithItsDataDocumentAsJson() {
        String vehicleId = TRUCK + "-alert";
        AlertEvent alert = alert(vehicleId, AlertType.SPEEDING, 137.2, 120.0);

        persistence.recordAlert(alert);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM alerts WHERE event_id = CAST(? AS UUID)", alert.eventId().toString());
        assertThat(row.get("vehicle_id")).isEqualTo(vehicleId);
        assertThat(row.get("type")).isEqualTo("SPEEDING_DETECTED");
        assertThat(row.get("severity")).isEqualTo("WARNING");
        assertThat(instantOf(row.get("occurred_at"))).isEqualTo(EVENT_AT);
        assertThat(instantOf(row.get("created_at"))).isEqualTo(WRITTEN_AT);

        // The column is JSONB, so the metadata of the alert is a document a query can address
        // rather than a string a reader has to parse again.
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT (metadata ->> 'speed')::double precision FROM alerts WHERE event_id = CAST(? AS UUID)",
                        Double.class,
                        alert.eventId().toString()))
                .isEqualTo(137.2);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT metadata ->> 'threshold' FROM alerts WHERE event_id = CAST(? AS UUID)",
                        String.class,
                        alert.eventId().toString()))
                .isEqualTo("120.0");
    }

    /**
     * The acceptance criterion of the milestone: a duplicate {@code eventId} never results in a
     * duplicate alert. Replaying the alert through the persistence port is a no-op, and the
     * constraint underneath refuses the row even when a writer bypasses the port.
     */
    @Test
    void storesADuplicateEventIdOnlyOnce() {
        String vehicleId = TRUCK + "-duplicate";
        AlertEvent alert = alert(vehicleId, AlertType.SPEEDING, 137.2, 120.0);

        persistence.recordAlert(alert);
        persistence.recordAlert(alert);
        persistence.recordAlert(alert);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM alerts WHERE event_id = CAST(? AS UUID)",
                        Integer.class,
                        alert.eventId().toString()))
                .as("three writes of one event id leave one row")
                .isEqualTo(1);

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO alerts (event_id, vehicle_id, type, severity, occurred_at, created_at, metadata)"
                                + " VALUES (CAST(? AS UUID), ?, ?, ?, ?, ?, CAST(? AS JSONB))",
                        alert.eventId().toString(),
                        vehicleId,
                        "SPEEDING_DETECTED",
                        "WARNING",
                        java.sql.Timestamp.from(EVENT_AT),
                        java.sql.Timestamp.from(WRITTEN_AT),
                        "{\"speed\": 137.2, \"threshold\": 120.0}"))
                .as("the unique event id is the backstop of the idempotent write")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM alerts WHERE event_id = CAST(? AS UUID)",
                        Integer.class,
                        alert.eventId().toString()))
                .isEqualTo(1);
    }

    @Test
    void keepsTheVehicleUniqueConstraintAndDifferentAlertsAsSeparateRows() {
        String vehicleId = TRUCK + "-distinct";
        persistence.recordVehicleState(
                vehicleId, state(39.4699, -0.3763, 80.0, VehicleStatus.MOVING, EVENT_AT));
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO vehicles (vehicle_id, created_at) VALUES (?, ?)",
                        vehicleId,
                        java.sql.Timestamp.from(WRITTEN_AT)))
                .isInstanceOf(DataIntegrityViolationException.class);

        persistence.recordAlert(alert(vehicleId, AlertType.SPEEDING, 137.2, 120.0));
        persistence.recordAlert(alert(vehicleId, AlertType.SPEEDING, 140.0, 120.0));
        persistence.recordAlert(alert(OTHER_TRUCK, AlertType.VEHICLE_STOPPED, 0.0, 50.0));

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM alerts WHERE vehicle_id = ?", Integer.class, vehicleId))
                .as("two alerts of one vehicle are two rows, unlike two writes of one alert")
                .isEqualTo(2);
    }

    private static AlertEvent alert(String vehicleId, AlertType type, double speed, double threshold) {
        return AlertEvent.create(type, vehicleId, EVENT_AT, new AlertData(speed, threshold));
    }

    private static VehicleState state(
            double latitude, double longitude, double speed, VehicleStatus status, Instant lastUpdate) {
        return new VehicleState(
                latitude, longitude, speed, TelemetryFixtures.DEMO_HEADING, lastUpdate, status, lastUpdate, 0.0);
    }

    private static List<String> existingTables() {
        return jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
    }

    private static List<String> uniqueConstraintsOf(String table) {
        return jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints"
                        + " WHERE table_schema = 'public' AND table_name = ? AND constraint_type = 'UNIQUE'",
                String.class,
                table);
    }

    private static String primaryKeyOf(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT constraint_name FROM information_schema.table_constraints"
                        + " WHERE table_schema = 'public' AND table_name = ? AND constraint_type = 'PRIMARY KEY'",
                String.class,
                table);
    }

    private static int countOf(String table, String column, String value) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
    }

    /** A {@code TIMESTAMP WITH TIME ZONE} is read back as an {@link java.time.OffsetDateTime}. */
    private static Instant instantOf(Object storedTimestamp) {
        if (storedTimestamp instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (storedTimestamp instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalArgumentException("unsupported timestamp value " + storedTimestamp);
    }

}

package com.coobi.logistics.streamprocessor.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.streamprocessor.event.AlertData;
import com.coobi.logistics.streamprocessor.event.AlertEvent;
import com.coobi.logistics.streamprocessor.event.AlertType;
import com.coobi.logistics.streamprocessor.processing.VehicleState;
import com.coobi.logistics.streamprocessor.processing.VehicleStatus;
import com.coobi.logistics.streamprocessor.support.TelemetryFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * MVP-4.1 to MVP-4.3: the shipped migrations and the production SQL, executed against an
 * in-memory database in PostgreSQL mode.
 *
 * <p>The point of this test is that it does not restate the schema: it applies the
 * migration files of the service and then drives the real persistence class over the result,
 * so a migration that stops matching the SQL, or an upsert that starts appending rows,
 * fails here.
 */
class JdbcTelemetryPersistenceTest {

    private static final String TRUCK = "TRUCK-00001";
    private static final String OTHER_TRUCK = "TRUCK-00002";
    private static final Instant EVENT_AT = Instant.parse("2026-09-21T09:15:00Z");
    private static final Instant WRITTEN_AT = Instant.parse("2026-09-21T09:20:00Z");
    private static final ObjectMapper OBJECT_MAPPER = TelemetryFixtures.objectMapper();

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private JdbcTelemetryPersistence persistence;

    @BeforeEach
    void migrateAndWireThePersistence() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:stream-processor-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        dataSource = h2;
        jdbcTemplate = new JdbcTemplate(dataSource);
        applyMigrations(jdbcTemplate);

        persistence = new JdbcTelemetryPersistence(
                jdbcTemplate,
                new DataSourceTransactionManager(dataSource),
                OBJECT_MAPPER,
                Clock.fixed(WRITTEN_AT, ZoneOffset.UTC));
    }

    @Test
    void createsTheVehicleAndItsLatestStateOnTheFirstTelemetry() {
        persistence.recordVehicleState(TRUCK, state(39.4699, -0.3763, 80.0, VehicleStatus.MOVING, EVENT_AT));

        assertThat(countVehicles()).isEqualTo(1);
        assertThat(countLatestState()).isEqualTo(1);
        Map<String, Object> row = latestStateOf(TRUCK);
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
        persistence.recordVehicleState(TRUCK, state(39.4699, -0.3763, 80.0, VehicleStatus.MOVING, EVENT_AT));
        persistence.recordVehicleState(
                TRUCK, state(39.47026, -0.3763, 0.0, VehicleStatus.STOPPED, EVENT_AT.plusSeconds(300)));

        assertThat(countVehicles()).as("the vehicle is created once").isEqualTo(1);
        assertThat(countLatestState()).as("one state row per vehicle").isEqualTo(1);
        Map<String, Object> row = latestStateOf(TRUCK);
        assertThat(row.get("latitude")).isEqualTo(39.47026);
        assertThat(row.get("speed")).isEqualTo(0.0);
        assertThat(row.get("status")).isEqualTo("STOPPED");
        assertThat(instantOf(row.get("last_update"))).isEqualTo(EVENT_AT.plusSeconds(300));
        assertThat(createdAtOf(TRUCK))
                .as("the vehicle was first seen at the first write")
                .isEqualTo(WRITTEN_AT);
    }

    @Test
    void keepsOneRowPerVehicle() {
        persistence.recordVehicleState(TRUCK, state(39.4699, -0.3763, 80.0, VehicleStatus.MOVING, EVENT_AT));
        persistence.recordVehicleState(OTHER_TRUCK, state(39.4699, -0.3763, 60.0, VehicleStatus.MOVING, EVENT_AT));
        persistence.recordVehicleState(
                TRUCK, state(39.47, -0.3763, 65.0, VehicleStatus.MOVING, EVENT_AT.plusSeconds(30)));

        assertThat(countVehicles()).isEqualTo(2);
        assertThat(countLatestState()).isEqualTo(2);
        assertThat(latestStateOf(TRUCK).get("speed")).isEqualTo(65.0);
        assertThat(latestStateOf(OTHER_TRUCK).get("speed")).isEqualTo(60.0);
    }

    @Test
    void retriesTheUpsertWhenAVehicleIsWrittenConcurrently() {
        // A writer that saw the vehicle as unknown between the lookup and the insert is the
        // race the retry exists for: the second attempt has to take the update branch.
        jdbcTemplate.update(
                "INSERT INTO vehicles (vehicle_id, created_at) VALUES (?, ?)",
                TRUCK,
                java.sql.Timestamp.from(WRITTEN_AT));
        JdbcTemplate racingTemplate = new JdbcTemplate(dataSource) {
            private boolean reportedUnknown;

            @Override
            public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
                if (!reportedUnknown && sql.contains("FROM vehicles")) {
                    reportedUnknown = true;
                    return List.of();
                }
                return super.queryForList(sql, elementType, args);
            }
        };
        JdbcTelemetryPersistence racing = new JdbcTelemetryPersistence(
                racingTemplate,
                new DataSourceTransactionManager(dataSource),
                OBJECT_MAPPER,
                Clock.fixed(WRITTEN_AT, ZoneOffset.UTC));

        racing.recordVehicleState(TRUCK, state(39.4699, -0.3763, 80.0, VehicleStatus.MOVING, EVENT_AT));

        assertThat(countVehicles()).isEqualTo(1);
        assertThat(countLatestState()).isEqualTo(1);
        assertThat(latestStateOf(TRUCK).get("status")).isEqualTo("MOVING");
    }

    @Test
    void storesTheSameAlertEventOnlyOnce() {
        AlertEvent alert = alert(AlertType.SPEEDING, 137.2, 120.0);

        persistence.recordAlert(alert);
        persistence.recordAlert(alert);
        persistence.recordAlert(alert);

        assertThat(countAlerts()).isEqualTo(1);
        Map<String, Object> row = alertRow(alert.eventId());
        assertThat(row.get("vehicle_id")).isEqualTo(TRUCK);
        assertThat(row.get("type")).isEqualTo("SPEEDING_DETECTED");
        assertThat(row.get("severity")).isEqualTo("WARNING");
        assertThat(instantOf(row.get("occurred_at"))).isEqualTo(EVENT_AT);
        assertThat(instantOf(row.get("created_at"))).isEqualTo(WRITTEN_AT);
    }

    @Test
    void preservesTheMetadataDocumentOfTheAlert() throws Exception {
        AlertEvent alert = alert(AlertType.VEHICLE_STOPPED, 0.0, 50.0);

        persistence.recordAlert(alert);

        String metadata = jdbcTemplate.queryForObject(
                "SELECT metadata FROM alerts WHERE event_id = CAST(? AS UUID)",
                String.class,
                alert.eventId().toString());
        JsonNode stored = jsonDocument(metadata);
        assertThat(stored.get("speed").asDouble()).isEqualTo(0.0);
        assertThat(stored.get("threshold").asDouble()).isEqualTo(50.0);
        assertThat(stored)
                .as("the stored document is the data document of the alert contract")
                .isEqualTo(OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(alert.data())));
    }

    @Test
    void storesDifferentAlertsAsSeparateRows() {
        persistence.recordAlert(alert(AlertType.SPEEDING, 137.2, 120.0));
        persistence.recordAlert(alert(AlertType.SPEEDING, 140.0, 120.0));
        persistence.recordAlert(alert(AlertType.VEHICLE_STOPPED, 0.0, 50.0));

        assertThat(countAlerts()).isEqualTo(3);
    }

    private AlertEvent alert(AlertType type, double speed, double threshold) {
        return AlertEvent.create(type, TRUCK, EVENT_AT, new AlertData(speed, threshold));
    }

    private VehicleState state(
            double latitude, double longitude, double speed, VehicleStatus status, Instant lastUpdate) {
        return new VehicleState(
                latitude, longitude, speed, TelemetryFixtures.DEMO_HEADING, lastUpdate, status, lastUpdate, 0.0);
    }

    private int countVehicles() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM vehicles", Integer.class);
    }

    private int countLatestState() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM vehicle_latest_state", Integer.class);
    }

    private int countAlerts() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM alerts", Integer.class);
    }

    private Map<String, Object> latestStateOf(String vehicleId) {
        return jdbcTemplate.queryForMap("SELECT * FROM vehicle_latest_state WHERE vehicle_id = ?", vehicleId);
    }

    private Map<String, Object> alertRow(UUID eventId) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM alerts WHERE event_id = CAST(? AS UUID)", eventId.toString());
    }

    private Instant createdAtOf(String vehicleId) {
        return instantOf(jdbcTemplate.queryForObject(
                "SELECT created_at FROM vehicles WHERE vehicle_id = ?", Object.class, vehicleId));
    }

    /**
     * A {@code TIMESTAMP WITH TIME ZONE} is read back as an {@link java.time.OffsetDateTime};
     * a driver that reports the legacy type instead is accepted as well.
     */
    private static Instant instantOf(Object storedTimestamp) {
        if (storedTimestamp instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (storedTimestamp instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalArgumentException("unsupported timestamp value " + storedTimestamp);
    }

    /**
     * The stored JSON document. A database that renders a JSON value as a quoted string
     * literal is unwrapped, so the assertion is about the document and not about the
     * rendering of the column type.
     */
    private static JsonNode jsonDocument(String stored) throws Exception {
        JsonNode document = OBJECT_MAPPER.readTree(stored);
        return document.isTextual() ? OBJECT_MAPPER.readTree(document.asText()) : document;
    }

    /** Runs the migration files of the service, the only definition of the schema. */
    private static void applyMigrations(JdbcTemplate jdbcTemplate) throws Exception {
        List<String> migrations = List.of(
                "db/migration/V1__create_vehicle_tables.sql", "db/migration/V2__create_alerts_table.sql");
        try (var connection = jdbcTemplate.getDataSource().getConnection()) {
            for (String migration : migrations) {
                ScriptUtils.executeSqlScript(
                        connection,
                        new EncodedResource(new ClassPathResource(migration), StandardCharsets.UTF_8));
            }
        }
    }
}

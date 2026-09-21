package com.coobi.logistics.logisticsapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coobi.logistics.logisticsapi.alert.AlertQueryService;
import com.coobi.logistics.logisticsapi.alert.AlertRepository;
import com.coobi.logistics.logisticsapi.alert.AlertResponse;
import com.coobi.logistics.logisticsapi.alert.AlertSeverity;
import com.coobi.logistics.logisticsapi.alert.AlertType;
import com.coobi.logistics.logisticsapi.web.ApiNotFoundException;
import com.coobi.logistics.logisticsapi.web.PageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MVP-5.3 against a real database: the three independent filters, the page, the ordering and
 * the embedding of the stored metadata.
 *
 * <p>The rows are written with SQL rather than through an entity, because the API never
 * writes: the fixtures stand in for the stream processor, which is the only writer of this
 * table (MVP-4.3).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AlertReadModelTest {

    private static final String SPEEDING = "{\"speed\":131.5,\"threshold\":120.0}";
    private static final String STOPPED = "{\"speed\":0.0,\"threshold\":50.0}";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AlertRepository alerts;

    private JdbcTemplate jdbc;
    private AlertQueryService service;

    @BeforeEach
    void wireTheQueryService() {
        jdbc = new JdbcTemplate(dataSource);
        service = new AlertQueryService(alerts, new ObjectMapper());
    }

    @Test
    void answersAlertsNewestFirst() {
        alert("TRUCK-00001", AlertType.SPEEDING_DETECTED, "09:00:00", SPEEDING);
        alert("TRUCK-00002", AlertType.VEHICLE_STOPPED_DETECTED, "09:05:00", STOPPED);
        alert("TRUCK-00001", AlertType.VEHICLE_STOPPED_DETECTED, "09:10:00", STOPPED);

        PageResponse<AlertResponse> page = service.page(null, null, null, 0, 20);

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.content()).extracting(AlertResponse::occurredAt).containsExactly(
                Instant.parse("2026-09-21T09:10:00Z"),
                Instant.parse("2026-09-21T09:05:00Z"),
                Instant.parse("2026-09-21T09:00:00Z"));
    }

    @Test
    void embedsTheStoredMetadataAsJson() {
        alert("TRUCK-00001", AlertType.SPEEDING_DETECTED, "09:00:00", SPEEDING);

        AlertResponse speeding = service.page(null, null, null, 0, 20).content().get(0);

        assertThat(speeding.vehicleId()).isEqualTo("TRUCK-00001");
        assertThat(speeding.type()).isEqualTo(AlertType.SPEEDING_DETECTED);
        assertThat(speeding.severity()).isEqualTo(AlertSeverity.WARNING);
        assertThat(speeding.eventId()).isNotNull();
        assertThat(speeding.occurredAt()).isEqualTo(Instant.parse("2026-09-21T09:00:00Z"));
        assertThat(speeding.createdAt()).isEqualTo(Instant.parse("2026-09-21T09:00:01Z"));
        assertThat(speeding.metadata().path("speed").asDouble()).isEqualTo(131.5);
        assertThat(speeding.metadata().path("threshold").asDouble()).isEqualTo(120.0);
    }

    @Test
    void filtersByVehicle() {
        alert("TRUCK-00001", AlertType.SPEEDING_DETECTED, "09:00:00", SPEEDING);
        alert("TRUCK-00002", AlertType.VEHICLE_STOPPED_DETECTED, "09:05:00", STOPPED);
        alert("TRUCK-00001", AlertType.VEHICLE_STOPPED_DETECTED, "09:10:00", STOPPED);

        PageResponse<AlertResponse> page = service.page("TRUCK-00001", null, null, 0, 20);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).allSatisfy(alert -> assertThat(alert.vehicleId()).isEqualTo("TRUCK-00001"));
    }

    @Test
    void filtersByType() {
        alert("TRUCK-00001", AlertType.SPEEDING_DETECTED, "09:00:00", SPEEDING);
        alert("TRUCK-00002", AlertType.VEHICLE_STOPPED_DETECTED, "09:05:00", STOPPED);

        PageResponse<AlertResponse> page = service.page(null, AlertType.VEHICLE_STOPPED_DETECTED, null, 0, 20);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().get(0).type()).isEqualTo(AlertType.VEHICLE_STOPPED_DETECTED);
    }

    @Test
    void filtersBySeverity() {
        alert("TRUCK-00001", AlertType.SPEEDING_DETECTED, "09:00:00", SPEEDING);
        alert("TRUCK-00002", AlertType.VEHICLE_STOPPED_DETECTED, "09:05:00", STOPPED);

        assertThat(service.page(null, null, AlertSeverity.WARNING, 0, 20).totalElements()).isEqualTo(2);
        assertThat(service.page(null, null, null, 0, 20).totalElements()).isEqualTo(2);
    }

    @Test
    void combinesTheFilters() {
        alert("TRUCK-00001", AlertType.SPEEDING_DETECTED, "09:00:00", SPEEDING);
        alert("TRUCK-00002", AlertType.VEHICLE_STOPPED_DETECTED, "09:05:00", STOPPED);
        alert("TRUCK-00001", AlertType.VEHICLE_STOPPED_DETECTED, "09:10:00", STOPPED);

        PageResponse<AlertResponse> page =
                service.page("TRUCK-00001", AlertType.VEHICLE_STOPPED_DETECTED, AlertSeverity.WARNING, 0, 20);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().get(0).occurredAt()).isEqualTo(Instant.parse("2026-09-21T09:10:00Z"));
    }

    @Test
    void treatsABlankVehicleAsNoFilter() {
        alert("TRUCK-00001", AlertType.SPEEDING_DETECTED, "09:00:00", SPEEDING);
        alert("TRUCK-00002", AlertType.VEHICLE_STOPPED_DETECTED, "09:05:00", STOPPED);

        // An empty query parameter means "not asked for": filtering by it would answer an
        // empty page that looks like missing data.
        assertThat(service.page("  ", null, null, 0, 20).totalElements()).isEqualTo(2);
    }

    @Test
    void pagesAlerts() {
        alert("TRUCK-00001", AlertType.SPEEDING_DETECTED, "09:00:00", SPEEDING);
        alert("TRUCK-00002", AlertType.VEHICLE_STOPPED_DETECTED, "09:05:00", STOPPED);
        alert("TRUCK-00001", AlertType.VEHICLE_STOPPED_DETECTED, "09:10:00", STOPPED);

        PageResponse<AlertResponse> first = service.page(null, null, null, 0, 2);
        PageResponse<AlertResponse> second = service.page(null, null, null, 1, 2);

        assertThat(first.content()).hasSize(2);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.first()).isTrue();
        assertThat(first.last()).isFalse();

        assertThat(second.content()).hasSize(1);
        assertThat(second.first()).isFalse();
        assertThat(second.last()).isTrue();
    }

    @Test
    void breaksTiesByTheNewestStoredRow() {
        alert("TRUCK-00001", AlertType.SPEEDING_DETECTED, "09:00:00", SPEEDING);
        alert("TRUCK-00002", AlertType.SPEEDING_DETECTED, "09:00:00", SPEEDING);

        // Two alerts of the same instant still have a total order, so paging cannot repeat or
        // drop one of them.
        assertThat(service.page(null, null, null, 0, 20).content())
                .extracting(AlertResponse::vehicleId)
                .containsExactly("TRUCK-00002", "TRUCK-00001");
    }

    @Test
    void answersNotFoundForAnUnknownAlert() {
        assertThatThrownBy(() -> service.byId(404L))
                .isInstanceOf(ApiNotFoundException.class)
                .hasMessage("no alert with id 404");
    }

    @Test
    void answersOneStoredAlert() {
        alert("TRUCK-00001", AlertType.SPEEDING_DETECTED, "09:00:00", SPEEDING);

        Long storedId = alerts.findAll().get(0).getId();
        AlertResponse stored = service.byId(storedId);

        assertThat(stored.id()).isEqualTo(storedId);
        assertThat(stored.vehicleId()).isEqualTo("TRUCK-00001");
    }

    private void alert(String vehicleId, AlertType type, String timeOfDay, String metadata) {
        Instant occurredAt = Instant.parse("2026-09-21T" + timeOfDay + "Z");
        // `FORMAT JSON` is how the in-memory database of the tests takes a JSON object; the
        // stream processor writes the same document with `CAST(... AS JSONB)` on PostgreSQL.
        jdbc.update(
                "INSERT INTO alerts (event_id, vehicle_id, type, severity, occurred_at, created_at, metadata)"
                        + " VALUES (CAST(? AS UUID), ?, ?, ?, ?, ?, ? FORMAT JSON)",
                UUID.randomUUID().toString(),
                vehicleId,
                type.name(),
                AlertSeverity.WARNING.name(),
                Timestamp.from(occurredAt),
                Timestamp.from(occurredAt.plusSeconds(1)),
                metadata);
    }
}

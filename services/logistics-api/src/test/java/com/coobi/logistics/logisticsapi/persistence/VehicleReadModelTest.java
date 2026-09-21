package com.coobi.logistics.logisticsapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coobi.logistics.logisticsapi.vehicle.VehicleLatestStateRepository;
import com.coobi.logistics.logisticsapi.vehicle.VehicleQueryService;
import com.coobi.logistics.logisticsapi.vehicle.VehicleResponse;
import com.coobi.logistics.logisticsapi.vehicle.VehicleStatus;
import com.coobi.logistics.logisticsapi.web.ApiNotFoundException;
import com.coobi.logistics.logisticsapi.web.PageResponse;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MVP-5.2 against a real database: the paging, the status filter and the mapping of the read
 * model to the response.
 *
 * <p>The rows are written with SQL rather than through an entity, because the API never
 * writes: the fixtures stand in for the stream processor, which is the only writer of these
 * tables (MVP-4).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class VehicleReadModelTest {

    private static final Instant FIRST_SEEN_AT = Instant.parse("2026-09-21T08:00:00Z");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private VehicleLatestStateRepository vehicles;

    private JdbcTemplate jdbc;
    private VehicleQueryService service;

    @BeforeEach
    void wireTheQueryService() {
        jdbc = new JdbcTemplate(dataSource);
        service = new VehicleQueryService(vehicles);
    }

    @Test
    void answersTheFleetNewestTelemetryFirst() {
        vehicle("TRUCK-00001", 39.4699, -0.3763, 80.0, 214.5, VehicleStatus.MOVING, "09:00:00");
        vehicle("TRUCK-00002", 39.47026, -0.3763, 0.0, 214.5, VehicleStatus.STOPPED, "09:10:00");
        vehicle("TRUCK-00003", 39.4710, -0.3763, 65.0, 180.0, VehicleStatus.MOVING, "09:05:00");

        PageResponse<VehicleResponse> page = service.page(null, 0, 20);

        assertThat(page.content()).extracting(VehicleResponse::vehicleId)
                .containsExactly("TRUCK-00002", "TRUCK-00003", "TRUCK-00001");
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.first()).isTrue();
        assertThat(page.last()).isTrue();

        VehicleResponse stopped = page.content().get(0);
        assertThat(stopped.latitude()).isEqualTo(39.47026);
        assertThat(stopped.longitude()).isEqualTo(-0.3763);
        assertThat(stopped.speed()).isEqualTo(0.0);
        assertThat(stopped.heading()).isEqualTo(214.5);
        assertThat(stopped.status()).isEqualTo(VehicleStatus.STOPPED);
        assertThat(stopped.lastUpdate()).isEqualTo(Instant.parse("2026-09-21T09:10:00Z"));
        assertThat(stopped.updatedAt()).isEqualTo(Instant.parse("2026-09-21T09:10:30Z"));
    }

    @Test
    void filtersByStatus() {
        vehicle("TRUCK-00001", 39.4699, -0.3763, 80.0, 214.5, VehicleStatus.MOVING, "09:00:00");
        vehicle("TRUCK-00002", 39.47026, -0.3763, 0.0, 214.5, VehicleStatus.STOPPED, "09:10:00");

        PageResponse<VehicleResponse> moving = service.page(VehicleStatus.MOVING, 0, 20);
        PageResponse<VehicleResponse> stopped = service.page(VehicleStatus.STOPPED, 0, 20);

        assertThat(moving.content()).extracting(VehicleResponse::vehicleId).containsExactly("TRUCK-00001");
        assertThat(stopped.content()).extracting(VehicleResponse::vehicleId).containsExactly("TRUCK-00002");
        assertThat(moving.totalElements()).isEqualTo(1);
    }

    @Test
    void pagesTheFleet() {
        vehicle("TRUCK-00001", 39.4699, -0.3763, 80.0, 214.5, VehicleStatus.MOVING, "09:00:00");
        vehicle("TRUCK-00002", 39.47026, -0.3763, 0.0, 214.5, VehicleStatus.STOPPED, "09:10:00");
        vehicle("TRUCK-00003", 39.4710, -0.3763, 65.0, 180.0, VehicleStatus.MOVING, "09:05:00");

        PageResponse<VehicleResponse> first = service.page(null, 0, 2);
        PageResponse<VehicleResponse> second = service.page(null, 1, 2);

        assertThat(first.content()).extracting(VehicleResponse::vehicleId)
                .containsExactly("TRUCK-00002", "TRUCK-00003");
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.first()).isTrue();
        assertThat(first.last()).isFalse();

        assertThat(second.content()).extracting(VehicleResponse::vehicleId).containsExactly("TRUCK-00001");
        assertThat(second.first()).isFalse();
        assertThat(second.last()).isTrue();
    }

    @Test
    void breaksTiesByVehicleId() {
        vehicle("TRUCK-00002", 39.4699, -0.3763, 80.0, 214.5, VehicleStatus.MOVING, "09:00:00");
        vehicle("TRUCK-00001", 39.4699, -0.3763, 70.0, 214.5, VehicleStatus.MOVING, "09:00:00");

        // Without the tie breaker a page boundary could drop or repeat a vehicle, so the
        // order of a page has to be total.
        assertThat(service.page(null, 0, 2).content())
                .extracting(VehicleResponse::vehicleId)
                .containsExactly("TRUCK-00001", "TRUCK-00002");
    }

    @Test
    void answersNotFoundForAVehicleThatWasNeverSeen() {
        assertThatThrownBy(() -> service.byId("TRUCK-00042"))
                .isInstanceOf(ApiNotFoundException.class)
                .hasMessage("no vehicle with id 'TRUCK-00042'");
    }

    @Test
    void answersTheLatestStateOfOneVehicle() {
        vehicle("TRUCK-00001", 39.4699, -0.3763, 80.0, 214.5, VehicleStatus.MOVING, "09:00:00");

        VehicleResponse truck = service.byId("TRUCK-00001");

        assertThat(truck.vehicleId()).isEqualTo("TRUCK-00001");
        assertThat(truck.speed()).isEqualTo(80.0);
    }

    private void vehicle(
            String vehicleId,
            double latitude,
            double longitude,
            double speed,
            double heading,
            VehicleStatus status,
            String timeOfDay) {
        Instant lastUpdate = Instant.parse("2026-09-21T" + timeOfDay + "Z");
        // Only the state table is written here: it is the one the fleet view reads, and the
        // registry row of a vehicle belongs to the processor that created it.
        jdbc.update(
                "INSERT INTO vehicle_latest_state"
                        + " (vehicle_id, latitude, longitude, speed, heading, status, last_update, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                vehicleId,
                latitude,
                longitude,
                speed,
                heading,
                status.name(),
                Timestamp.from(lastUpdate),
                Timestamp.from(lastUpdate.plusSeconds(30)));
    }
}

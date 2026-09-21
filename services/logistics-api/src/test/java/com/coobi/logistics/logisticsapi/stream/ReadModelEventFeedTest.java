package com.coobi.logistics.logisticsapi.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.logisticsapi.alert.AlertQueryService;
import com.coobi.logistics.logisticsapi.alert.AlertRepository;
import com.coobi.logistics.logisticsapi.alert.AlertResponse;
import com.coobi.logistics.logisticsapi.alert.AlertSeverity;
import com.coobi.logistics.logisticsapi.alert.AlertType;
import com.coobi.logistics.logisticsapi.vehicle.VehicleLatestStateRepository;
import com.coobi.logistics.logisticsapi.vehicle.VehicleQueryService;
import com.coobi.logistics.logisticsapi.vehicle.VehicleResponse;
import com.coobi.logistics.logisticsapi.vehicle.VehicleStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MVP-6.1 against a real database: what the feed streams, what it refuses to replay, and how a
 * burst is sampled instead of queued.
 *
 * <p>The rows are written with SQL rather than through an entity, because the API never writes:
 * the fixtures stand in for the stream processor, which is the only writer of these tables.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReadModelEventFeedTest {

    private static final String SPEEDING = "{\"speed\":131.5,\"threshold\":120.0}";
    private static final String STOPPED = "{\"speed\":0.0,\"threshold\":50.0}";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AlertRepository alerts;

    @Autowired
    private VehicleLatestStateRepository vehicles;

    private JdbcTemplate jdbc;
    private ReadModelEventFeed feed;

    @BeforeEach
    void wireTheFeed() {
        jdbc = new JdbcTemplate(dataSource);
        feed = new ReadModelEventFeed(
                new AlertQueryService(alerts, new ObjectMapper()),
                new VehicleQueryService(vehicles));
    }

    @Test
    void streamsTheAlertsStoredSinceThePreviousTick() {
        feed.start();
        alert("TRUCK-00001", AlertType.SPEEDING_DETECTED, "09:00:00", SPEEDING);
        alert("TRUCK-00002", AlertType.VEHICLE_STOPPED_DETECTED, "09:05:00", STOPPED);

        List<StreamEvent> events = feed.poll(10, 10);

        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(event -> assertThat(event.type()).isEqualTo(StreamEvent.Type.ALERT));
        assertThat(events).extracting(event -> ((AlertResponse) event.payload()).vehicleId())
                .as("a client appends what it receives, so the batch is sent oldest first")
                .containsExactly("TRUCK-00001", "TRUCK-00002");
        AlertResponse first = (AlertResponse) events.get(0).payload();
        assertThat(first.type()).isEqualTo(AlertType.SPEEDING_DETECTED);
        assertThat(first.severity()).isEqualTo(AlertSeverity.WARNING);
        assertThat(first.metadata().path("speed").asDouble()).isEqualTo(131.5);
    }

    @Test
    void streamsNothingBeforeABrowserHasPlacedItAtTheLiveEdge() {
        alert("TRUCK-00001", AlertType.SPEEDING_DETECTED, "09:00:00", SPEEDING);

        assertThat(feed.poll(10, 10)).isEmpty();
    }

    @Test
    void doesNotReplayTheHistoryWhenAConnectionOpens() {
        alert("TRUCK-00001", AlertType.SPEEDING_DETECTED, "09:00:00", SPEEDING);
        vehicle("TRUCK-00001", VehicleStatus.MOVING, "09:00:00");

        feed.start();

        assertThat(feed.poll(10, 10))
                .as("the history is a page of the REST endpoints, not a stream of events")
                .isEmpty();
    }

    @Test
    void samplesABurstInsteadOfQueueingIt() {
        feed.start();
        for (int index = 0; index < 10; index++) {
            alert("TRUCK-0000" + index, AlertType.SPEEDING_DETECTED, "09:0" + (index % 10) + ":00", SPEEDING);
        }

        List<StreamEvent> batch = feed.poll(3, 0);

        assertThat(batch).as("one tick carries at most what it is allowed to carry").hasSize(3);
        assertThat(batch).extracting(event -> ((AlertResponse) event.payload()).vehicleId())
                .as("the newest events are the ones that are kept")
                .containsExactly("TRUCK-00007", "TRUCK-00008", "TRUCK-00009");
        assertThat(feed.poll(3, 0))
                .as("the skipped alerts are behind the cursor, not a backlog of the stream")
                .isEmpty();
    }

    @Test
    void streamsTheVehiclesThatMovedSinceThePreviousTick() {
        feed.start();
        vehicle("TRUCK-00001", VehicleStatus.MOVING, "09:00:00");
        vehicle("TRUCK-00002", VehicleStatus.STOPPED, "09:05:00");
        vehicle("TRUCK-00003", VehicleStatus.MOVING, "09:10:00");

        List<StreamEvent> events = feed.poll(0, 2);

        assertThat(events).extracting(event -> ((VehicleResponse) event.payload()).vehicleId())
                .containsExactly("TRUCK-00002", "TRUCK-00003");
        assertThat(events).allSatisfy(event -> assertThat(event.type()).isEqualTo(StreamEvent.Type.VEHICLE));
        assertThat(feed.poll(0, 2)).isEmpty();
    }

    @Test
    void reportsBothFamiliesOfEventInOneTick() {
        feed.start();
        alert("TRUCK-00001", AlertType.SPEEDING_DETECTED, "09:00:00", SPEEDING);
        vehicle("TRUCK-00002", VehicleStatus.MOVING, "09:00:00");

        List<StreamEvent> events = feed.poll(5, 5);

        assertThat(events).extracting(StreamEvent::type)
                .containsExactly(StreamEvent.Type.ALERT, StreamEvent.Type.VEHICLE);
    }

    @Test
    void repositionsAtTheLiveEdgeWhenANewConnectionOpens() {
        feed.start();
        alert("TRUCK-00001", AlertType.SPEEDING_DETECTED, "09:00:00", SPEEDING);
        assertThat(feed.poll(10, 10)).hasSize(1);

        // No browser was connected while this alert was stored, and the connection that arrives
        // now is live, not a replay: what happened in between is a page of /api/v1/alerts.
        alert("TRUCK-00002", AlertType.SPEEDING_DETECTED, "09:01:00", SPEEDING);
        feed.start();
        assertThat(feed.poll(10, 10)).isEmpty();

        alert("TRUCK-00003", AlertType.SPEEDING_DETECTED, "09:02:00", SPEEDING);
        assertThat(feed.poll(10, 10)).extracting(event -> ((AlertResponse) event.payload()).vehicleId())
                .containsExactly("TRUCK-00003");
    }

    private void alert(String vehicleId, AlertType type, String timeOfDay, String metadata) {
        Instant occurredAt = Instant.parse("2026-09-21T" + timeOfDay + "Z");
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

    private void vehicle(String vehicleId, VehicleStatus status, String timeOfDay) {
        Instant lastUpdate = Instant.parse("2026-09-21T" + timeOfDay + "Z");
        jdbc.update(
                "INSERT INTO vehicle_latest_state"
                        + " (vehicle_id, latitude, longitude, speed, heading, status, last_update, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                vehicleId,
                39.4699,
                -0.3763,
                80.0,
                214.5,
                status.name(),
                Timestamp.from(lastUpdate),
                Timestamp.from(lastUpdate));
    }
}

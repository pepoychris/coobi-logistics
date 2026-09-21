package com.coobi.logistics.logisticsapi.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.logisticsapi.support.Await;
import com.coobi.logistics.logisticsapi.support.SseClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * MVP-6 over real HTTP: a browser opens both streams, receives the events that happen while it
 * is connected, receives no more than the configuration allows, and is let go of when it
 * disconnects.
 *
 * <p>The properties below are the ones of a stream that is watched closely - a short interval
 * and a small sample - so the endpoints are asserted without a test that waits for the shipped
 * interval. How many browsers a stream serves is read over the Actuator endpoint of this
 * service, which is where an operator reads it too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "coobi.stream.events.poll-interval=100ms",
    "coobi.stream.events.max-alerts-per-poll=3",
    "coobi.stream.events.max-vehicles-per-poll=2",
    "coobi.stream.statistics.interval=250ms",
    "coobi.stream.client.heartbeat-interval=250ms",
    "logging.level.com.coobi.logistics.logisticsapi.stream=DEBUG"
})
class StreamingEndpointsTest {

    private static final String SPEEDING = "{\"speed\":131.5,\"threshold\":120.0}";
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void wireTheFixtures() {
        jdbc = new JdbcTemplate(dataSource);
        // Whatever a previous test left behind, this one starts from a quiet service.
        Await.until("the event stream to have no connection", () -> subscribers("events") == 0);
    }

    @Test
    void opensAnEventStreamThatStaysOpen() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port));
            socket.setSoTimeout((int) PATIENCE.toMillis());
            socket.getOutputStream().write(request("events"));
            socket.getOutputStream().flush();

            String response = readUntil(socket.getInputStream(), "retry:3000");

            assertThat(response).startsWith("HTTP/1.1 200");
            assertThat(response).containsIgnoringCase("Content-Type: text/event-stream");
            assertThat(response)
                    .as("the response headers arrive with the first frame, so a browser reports "
                            + "the connection as open instead of waiting for the first event")
                    .contains("retry:3000")
                    .contains(":connected");
            Await.until("the stream to count its connection", () -> subscribers("events") == 1);
        }
    }

    @Test
    void streamsTheAlertsAndVehiclesThatHappenWhileItIsOpen() throws Exception {
        try (SseClient browser = new SseClient(uri("/api/v1/stream/events"))) {
            assertThat(browser.status()).isEqualTo(200);
            assertThat(browser.header("Content-Type")).startsWith("text/event-stream");
            assertThat(browser.awaitFrame(PATIENCE)).contains(":connected");

            alert("TRUCK-STREAM-0001");
            String alert = browser.awaitEvent("alert", PATIENCE);

            assertThat(alert).contains("\"vehicleId\":\"TRUCK-STREAM-0001\"").contains("SPEEDING_DETECTED");
            assertThat(alert).contains("\"metadata\":{\"speed\":131.5,\"threshold\":120.0}");

            vehicle("TRUCK-STREAM-0002");
            String moving = browser.awaitEvent("vehicle", PATIENCE);

            assertThat(moving).contains("\"vehicleId\":\"TRUCK-STREAM-0002\"").contains("\"status\":\"MOVING\"");
        }
    }

    @Test
    void boundsHowMuchOneConnectionReceives() throws Exception {
        try (SseClient browser = new SseClient(uri("/api/v1/stream/events"))) {
            assertThat(browser.awaitFrame(PATIENCE)).contains(":connected");

            // One burst, stored in one transaction: a tick sees all of it and has to sample it.
            List<Object[]> burst = new ArrayList<>();
            for (int index = 0; index < 10; index++) {
                burst.add(alertRow("TRUCK-BURST-000" + index));
            }
            jdbc.batchUpdate(
                    "INSERT INTO alerts (event_id, vehicle_id, type, severity, occurred_at, created_at, metadata)"
                            + " VALUES (CAST(? AS UUID), ?, ?, ?, ?, ?, ? FORMAT JSON)",
                    burst);

            List<String> received = browser.collect("alert", Duration.ofSeconds(2));

            assertThat(received)
                    .as("the volume a browser receives is set by the configuration, not by the "
                            + "throughput of the pipeline")
                    .hasSize(3);
            assertThat(received.get(2)).contains("TRUCK-BURST-0009");
            assertThat(received.get(0)).contains("TRUCK-BURST-0007");
        }
    }

    @Test
    void streamsLiveStatisticsWithoutPolling() throws Exception {
        vehicle("TRUCK-STATS-0001");

        try (SseClient browser = new SseClient(uri("/api/v1/stream/statistics"))) {
            assertThat(browser.status()).isEqualTo(200);
            assertThat(browser.awaitFrame(PATIENCE)).contains(":connected");

            long before = System.nanoTime();
            String first = browser.awaitEvent("statistics", PATIENCE);
            String second = browser.awaitEvent("statistics", PATIENCE);
            long intervalMillis = (System.nanoTime() - before) / 1_000_000;

            JsonNode live = new ObjectMapper().readTree(first);
            assertThat(live.fieldNames()).toIterable().containsExactly(
                    "processedEvents", "eventsPerSecond", "activeVehicles", "alertsGenerated", "uptimeSeconds");
            assertThat(live.path("activeVehicles").asLong())
                    .as("the values are the live ones, not a fixture")
                    .isGreaterThanOrEqualTo(1L);
            assertThat(live.path("processedEvents").isNull())
                    .as("a source that cannot be read is reported as absent, in the stream too")
                    .isTrue();
            assertThat(new ObjectMapper().readTree(second).path("uptimeSeconds").asLong()).isGreaterThanOrEqualTo(0L);
            assertThat(intervalMillis)
                    .as("the stream is never faster than its configured interval")
                    .isGreaterThanOrEqualTo(150L);
            assertThat(browser.collect("statistics", Duration.ofSeconds(2)))
                    .as("the interval in effect is the configured one, not the shipped default")
                    .hasSizeGreaterThanOrEqualTo(4);
        }
    }

    @Test
    void freesTheConnectionWhenTheBrowserDisconnects() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port));
            socket.setSoTimeout((int) PATIENCE.toMillis());
            socket.getOutputStream().write(request("events"));
            socket.getOutputStream().flush();
            readUntil(socket.getInputStream(), "retry:3000");
            Await.until("the stream to count its connection", () -> subscribers("events") == 1);
        }

        Await.until("the stream to let go of the connection that went away", () -> subscribers("events") == 0);
    }

    private byte[] request(String stream) {
        return ("GET /api/v1/stream/" + stream + " HTTP/1.1\r\n"
                        + "Host: localhost:" + port + "\r\n"
                        + "Accept: text/event-stream\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** Reads the connections one stream currently serves, from the metrics of this service. */
    private double subscribers(String stream) {
        JsonNode metric = rest.getForObject(
                "http://localhost:" + port + "/actuator/metrics/coobi.stream.subscribers?tag=stream:" + stream,
                JsonNode.class);
        return metric.path("measurements").path(0).path("value").asDouble();
    }

    private static String readUntil(InputStream response, String marker) throws IOException {
        StringBuilder text = new StringBuilder();
        byte[] buffer = new byte[256];
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (System.nanoTime() < deadline && text.indexOf(marker) < 0) {
            int read = response.read(buffer);
            if (read < 0) {
                break;
            }
            text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return text.toString();
    }

    private Object[] alertRow(String vehicleId) {
        Instant occurredAt = Instant.now();
        return new Object[] {
            UUID.randomUUID().toString(),
            vehicleId,
            "SPEEDING_DETECTED",
            "WARNING",
            Timestamp.from(occurredAt),
            Timestamp.from(occurredAt),
            SPEEDING
        };
    }

    private void alert(String vehicleId) {
        jdbc.update(
                "INSERT INTO alerts (event_id, vehicle_id, type, severity, occurred_at, created_at, metadata)"
                        + " VALUES (CAST(? AS UUID), ?, ?, ?, ?, ?, ? FORMAT JSON)",
                alertRow(vehicleId));
    }

    private void vehicle(String vehicleId) {
        // One millisecond after now: strictly newer than anything stored before it, which is
        // what the cursor of the feed compares against.
        Instant lastUpdate = Instant.now().plusMillis(1);
        jdbc.update(
                "INSERT INTO vehicle_latest_state"
                        + " (vehicle_id, latitude, longitude, speed, heading, status, last_update, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                vehicleId,
                39.4699,
                -0.3763,
                80.0,
                214.5,
                "MOVING",
                Timestamp.from(lastUpdate),
                Timestamp.from(lastUpdate));
    }
}

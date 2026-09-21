package com.coobi.logistics.streamprocessor.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.streamprocessor.config.KafkaTopicsProperties;
import com.coobi.logistics.streamprocessor.config.ProcessingProperties;
import com.coobi.logistics.streamprocessor.event.AlertEvent;
import com.coobi.logistics.streamprocessor.support.RecordingPersistence;
import com.coobi.logistics.streamprocessor.support.TelemetryFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * MVP-4.2 and MVP-4.3 at the integration point: what the topology persists.
 *
 * <p>The wiring of the persistence port is driven through the real topology with a
 * recorder standing in for the database, so the test covers which records reach the port,
 * in which order, and that a rejected record never does. The idempotency of the statements
 * themselves is covered by {@code JdbcTelemetryPersistenceTest} against the migrations.
 */
class TelemetryPersistenceTopologyTest {

    private static final double SPEED_LIMIT = 120.0;
    private static final Instant START = Instant.parse("2026-09-21T09:15:00Z");
    private static final Instant FAILED_AT = Instant.parse("2026-09-21T09:16:00Z");
    private static final String TRUCK = "TRUCK-00001";

    private final ObjectMapper objectMapper = TelemetryFixtures.objectMapper();
    private final List<TopologyTestDriver> drivers = new ArrayList<>();

    @AfterAll
    static void closeValidator() {
        TelemetryFixtures.closeValidator();
    }

    @AfterEach
    void closeDrivers() {
        drivers.forEach(TopologyTestDriver::close);
        drivers.clear();
    }

    @Test
    void overwritesThePersistedStateOfTheVehicleForEveryAcceptedRecord() {
        Harness harness = harness();

        harness.send(TRUCK, START, 80.0, TelemetryFixtures.DEMO_LATITUDE);
        harness.send(TRUCK, START.plusSeconds(30), 95.5, 39.47008);

        assertThat(harness.persistence().stateWrites()).containsExactly(TRUCK, TRUCK);
        assertThat(harness.persistence().latestState()).containsOnlyKeys(TRUCK);
        VehicleState persisted = harness.persistence().latestState().get(TRUCK);
        assertThat(persisted.speed()).isEqualTo(95.5);
        assertThat(persisted.latitude()).isEqualTo(39.47008);
        assertThat(persisted.lastUpdate()).isEqualTo(START.plusSeconds(30));
        assertThat(persisted.status()).isEqualTo(VehicleStatus.MOVING);
    }

    @Test
    void persistsTheStateOfEachVehicleUnderItsOwnKey() {
        Harness harness = harness();

        harness.send(TRUCK, START, 60.0, TelemetryFixtures.DEMO_LATITUDE);
        harness.send("TRUCK-00002", START, 140.0, TelemetryFixtures.DEMO_LATITUDE);

        assertThat(harness.persistence().latestState()).containsOnlyKeys(TRUCK, "TRUCK-00002");
        assertThat(harness.persistence().latestState().get("TRUCK-00002").speed())
                .isEqualTo(140.0);
    }

    @Test
    void neverPersistsARejectedRecord() {
        Harness harness = harness();

        harness.send(TRUCK, START, 80.0, TelemetryFixtures.DEMO_LATITUDE);
        harness.send(TRUCK, START.plusSeconds(10), 80.0, 91.0);

        assertThat(harness.deadLetters().isEmpty()).isFalse();
        assertThat(harness.persistence().stateWrites()).containsExactly(TRUCK);
        assertThat(harness.persistence().latestState().get(TRUCK).lastUpdate())
                .as("the rejected record never reached the port")
                .isEqualTo(START);
    }

    @Test
    void persistsAStoppedAlertExactlyOnce() {
        Harness harness = harness();

        for (long elapsed = 0; elapsed <= 300; elapsed += 60) {
            harness.send(TRUCK, START.plusSeconds(elapsed), 0.0, TelemetryFixtures.DEMO_LATITUDE);
        }
        // Telemetry of the already stopped vehicle produces no second alert.
        harness.send(TRUCK, START.plusSeconds(600), 0.0, TelemetryFixtures.DEMO_LATITUDE);

        List<AlertEvent> alerts = harness.persistence().alerts();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).eventType()).isEqualTo("VEHICLE_STOPPED_DETECTED");
        assertThat(alerts.get(0).vehicleId()).isEqualTo(TRUCK);
        assertThat(alerts.get(0).timestamp()).isEqualTo(START.plusSeconds(300));

        KeyValue<String, String> published = harness.alerts().readKeyValue();
        assertThat(published.value)
                .as("the stored alert is the published alert")
                .contains(alerts.get(0).eventId().toString());
    }

    @Test
    void persistsASpeedingAlertOncePerCrossing() {
        Harness harness = harness();

        harness.send(TRUCK, START, 140.0, TelemetryFixtures.DEMO_LATITUDE);
        harness.send(TRUCK, START.plusSeconds(10), 150.0, TelemetryFixtures.DEMO_LATITUDE);
        harness.send(TRUCK, START.plusSeconds(20), 90.0, TelemetryFixtures.DEMO_LATITUDE);
        harness.send(TRUCK, START.plusSeconds(30), 130.0, TelemetryFixtures.DEMO_LATITUDE);

        List<AlertEvent> alerts = harness.persistence().alerts();
        assertThat(alerts).hasSize(2);
        assertThat(alerts).allSatisfy(alert -> assertThat(alert.eventType()).isEqualTo("SPEEDING_DETECTED"));
        assertThat(harness.alerts().readKeyValuesToList()).hasSize(2);
    }

    private Harness harness() {
        ProcessingProperties processing = new ProcessingProperties();
        processing.setSpeedLimitKph(SPEED_LIMIT);
        KafkaTopicsProperties topics = new KafkaTopicsProperties();
        RecordingPersistence persistence = new RecordingPersistence();

        StreamsBuilder builder = new StreamsBuilder();
        new TelemetryTopologyConfiguration()
                .telemetryProcessingTopology(
                        builder,
                        objectMapper,
                        TelemetryFixtures.validator(),
                        processing,
                        topics,
                        Clock.fixed(FAILED_AT, ZoneOffset.UTC),
                        persistence);

        TopologyTestDriver driver = new TopologyTestDriver(builder.build(), streamsConfiguration());
        drivers.add(driver);

        return new Harness(
                driver,
                persistence,
                driver.createInputTopic(topics.locationTopic(), new StringSerializer(), new StringSerializer()),
                driver.createOutputTopic(topics.alertTopic(), new StringDeserializer(), new StringDeserializer()),
                driver.createOutputTopic(topics.deadLetterTopic(), new StringDeserializer(), new StringDeserializer()));
    }

    private static Properties streamsConfiguration() {
        Properties properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "stream-processor-persistence-test");
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        return properties;
    }

    private record Harness(
            TopologyTestDriver driver,
            RecordingPersistence persistence,
            TestInputTopic<String, String> telemetry,
            TestOutputTopic<String, String> alerts,
            TestOutputTopic<String, String> deadLetters) {

        void send(String vehicleId, Instant timestamp, double speed, double latitude) {
            telemetry.pipeInput(
                    vehicleId,
                    TelemetryFixtures.payload(
                            vehicleId, timestamp, speed, latitude, TelemetryFixtures.DEMO_LONGITUDE),
                    timestamp);
        }
    }
}

package com.coobi.logistics.streamprocessor.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.coobi.logistics.streamprocessor.integration.support.KafkaTopicProbe;
import com.coobi.logistics.streamprocessor.support.RecordingPersistence;
import com.coobi.logistics.streamprocessor.support.TelemetryFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * MVP-9.2: telemetry published to a real Kafka broker crosses the real topology and is
 * observed on the alert and dead letter topics.
 *
 * <pre>
 * produce telemetry -> Kafka -> stream processor -> alert
 *                                               \-> dead letter topic, for invalid records
 * </pre>
 *
 * <p>The container runs {@code apache/kafka:4.3.1} in KRaft mode, the same image and the same
 * single-node configuration as {@code compose.yml}. The broker is real: the topology is not
 * driven by {@code TopologyTestDriver} here, and the records are produced and consumed
 * through ordinary Kafka clients.
 *
 * <p>Everything else the service needs is kept out of the way on purpose, so a failure points
 * at the Kafka path rather than at a second moving part: the persistence port is replaced by
 * {@link RecordingPersistence} (the database is not this test's subject; MVP-9.3 covers it
 * against a real PostgreSQL) and the datasource points at an in-memory database whose
 * migrations are not applied, because nothing in this test writes to it.
 *
 * <p>The test class is skipped with the reason Testcontainers reports when no Docker daemon is
 * available, and it never sleeps: the assertions poll the topics with Awaitility.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.kafka.streams.application-id=coobi-stream-processor-kafka-it",
            "spring.kafka.streams.properties.num.stream.threads=1",
            "spring.kafka.streams.properties.commit.interval.ms=100",
            // The topology declares its serdes per node; the defaults keep the changelog
            // topics of the state stores readable strings as well.
            "spring.kafka.streams.properties.default.key.serde=org.apache.kafka.common.serialization.Serdes$StringSerde",
            "spring.kafka.streams.properties.default.value.serde=org.apache.kafka.common.serialization.Serdes$StringSerde",
            "coobi.kafka.initialization.enabled=true",
            "spring.datasource.url=jdbc:h2:mem:stream-processor-kafka-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.enabled=false",
            "logging.level.org.apache.kafka=WARN"
        })
class TelemetryPipelineIT {

    private static final String LOCATION_TOPIC = "logistics.vehicle.location.v1";
    private static final String ALERT_TOPIC = "logistics.alert.v1";
    private static final String DEAD_LETTER_TOPIC = "logistics.vehicle.location.dlq.v1";

    private static final double SPEED_LIMIT = 120.0;
    private static final double SPEEDING_SPEED = 137.2;
    private static final Instant EVENT_AT = Instant.parse("2026-09-21T10:00:00.000Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private static final ObjectMapper OBJECT_MAPPER = TelemetryFixtures.objectMapper();

    @Container
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    @DynamicPropertySource
    static void brokers(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private RecordingPersistence persistence;

    /**
     * A vehicle that stays above the speed limit is reported once, on the alert topic, and its
     * state is written through the persistence port of the topology.
     */
    @Test
    void turnsSpeedingTelemetryIntoAnAlertOnTheAlertTopic() {
        String vehicleId = "TRUCK-91001";

        try (KafkaTopicProbe alerts = KafkaTopicProbe.subscribingTo(bootstrapServers(), ALERT_TOPIC)) {
            publish(vehicleId, telemetry(vehicleId, EVENT_AT, 80.0));
            publish(vehicleId, telemetry(vehicleId, EVENT_AT.plusSeconds(1), SPEEDING_SPEED));

            await().atMost(TIMEOUT).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
                List<ConsumerRecord<String, String>> published = alerts.recordsWithKey(vehicleId);
                assertThat(published)
                        .as("one crossing of the speed limit produces exactly one alert")
                        .hasSize(1);

                JsonNode alert = read(published.get(0).value());
                assertThat(alert.path("eventType").asText()).isEqualTo("SPEEDING_DETECTED");
                assertThat(alert.path("version").asInt()).isEqualTo(1);
                assertThat(alert.path("vehicleId").asText()).isEqualTo(vehicleId);
                assertThat(alert.path("severity").asText()).isEqualTo("WARNING");
                assertThat(alert.path("timestamp").asText())
                        .as("the alert carries the timestamp of the telemetry that crossed the limit")
                        .isEqualTo(EVENT_AT.plusSeconds(1).toString());
                assertThat(alert.path("data").path("speed").asDouble()).isEqualTo(SPEEDING_SPEED);
                assertThat(alert.path("data").path("threshold").asDouble()).isEqualTo(SPEED_LIMIT);
                assertThat(alert.path("eventId").asText())
                        .as("the alert identifies itself, which is what makes the row idempotent")
                        .isNotBlank();
            });
        }

        assertThat(persistence.latestState()).containsKey(vehicleId);
        assertThat(persistence.latestState().get(vehicleId).speed()).isEqualTo(SPEEDING_SPEED);
        assertThat(persistence.alerts())
                // The context - and with it the recorder - is shared by the two tests of this
                // class, so the assertion is about this vehicle rather than about the total.
                .filteredOn(alert -> vehicleId.equals(alert.vehicleId()))
                .as("the alert reached the persistence port of the topology as well as the topic")
                .hasSize(1);
    }

    /**
     * An invalid record reaches the dead letter topic with its payload and the reason, and the
     * stream keeps processing: the valid telemetry published after it still produces an alert.
     */
    @Test
    void routesInvalidTelemetryToTheDeadLetterTopicAndKeepsProcessing() {
        String brokenVehicleId = "TRUCK-91002";
        String validVehicleId = "TRUCK-91003";
        String invalidPayload = telemetryWithLatitudeOutsideTheContract(brokenVehicleId);

        try (KafkaTopicProbe deadLetters =
                        KafkaTopicProbe.subscribingTo(bootstrapServers(), DEAD_LETTER_TOPIC);
                KafkaTopicProbe alerts = KafkaTopicProbe.subscribingTo(bootstrapServers(), ALERT_TOPIC)) {

            publish(brokenVehicleId, invalidPayload);
            publish(validVehicleId, telemetry(validVehicleId, EVENT_AT, 80.0));
            publish(validVehicleId, telemetry(validVehicleId, EVENT_AT.plusSeconds(1), SPEEDING_SPEED));

            await().atMost(TIMEOUT).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
                ConsumerRecord<String, String> deadLetter = deadLetters
                        .latestRecordWithKey(brokenVehicleId)
                        .orElseThrow(() -> new AssertionError("no dead letter record for " + brokenVehicleId));

                JsonNode envelope = read(deadLetter.value());
                assertThat(envelope.path("originalEvent").asText())
                        .as("the rejected payload is kept exactly as it was consumed")
                        .isEqualTo(invalidPayload);
                assertThat(envelope.path("error").asText())
                        .as("the reason names the field that violated the contract")
                        .contains("latitude");
                assertThat(envelope.path("sourceTopic").asText()).isEqualTo(LOCATION_TOPIC);
                assertThat(envelope.path("failedAt").asText()).isNotBlank();
            });

            await().atMost(TIMEOUT).pollInterval(POLL_INTERVAL).untilAsserted(() -> assertThat(alerts
                            .recordsWithKey(validVehicleId))
                    .as("a rejected record does not stop the records behind it")
                    .hasSize(1));
        }

        assertThat(persistence.latestState())
                .as("a rejected record never writes state")
                .doesNotContainKey(brokenVehicleId);
    }

    private String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    /** Publishes one record and waits for the broker to acknowledge it. */
    private void publish(String key, String payload) {
        kafkaTemplate.send(LOCATION_TOPIC, key, payload).join();
    }

    private static String telemetry(String vehicleId, Instant timestamp, double speed) {
        return TelemetryFixtures.payload(
                vehicleId,
                timestamp,
                speed,
                TelemetryFixtures.DEMO_LATITUDE,
                TelemetryFixtures.DEMO_LONGITUDE);
    }

    /**
     * A payload that is structurally a version-1 event but violates the contract, built as a
     * document rather than through the event record so the record cannot reject it first.
     */
    private static String telemetryWithLatitudeOutsideTheContract(String vehicleId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("latitude", 91.0);
        data.put("longitude", TelemetryFixtures.DEMO_LONGITUDE);
        data.put("speed", 80.0);
        data.put("heading", TelemetryFixtures.DEMO_HEADING);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("eventType", "VEHICLE_LOCATION_UPDATED");
        payload.put("version", 1);
        payload.put("vehicleId", vehicleId);
        payload.put("timestamp", EVENT_AT.toString());
        payload.put("data", data);

        return TelemetryFixtures.serialize(payload);
    }

    private static JsonNode read(String json) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception failure) {
            throw new IllegalStateException("reading " + json + " failed", failure);
        }
    }

    /**
     * Replaces the persistence port of the topology, so this test observes the alerts of a real
     * broker without a database behind them. The JDBC implementation stays in the context; the
     * recording one is what the topology receives.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingPersistenceConfiguration {

        @Bean
        @Primary
        RecordingPersistence recordingPersistence() {
            return new RecordingPersistence();
        }
    }
}

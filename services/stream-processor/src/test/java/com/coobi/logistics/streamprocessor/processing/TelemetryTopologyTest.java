package com.coobi.logistics.streamprocessor.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.streamprocessor.config.KafkaTopicsProperties;
import com.coobi.logistics.streamprocessor.config.ProcessingProperties;
import com.coobi.logistics.streamprocessor.support.TelemetryFixtures;
import com.coobi.logistics.streamprocessor.support.RecordingPersistence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.state.KeyValueStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * MVP-2.1 to MVP-2.3: the wired topology, driven without a broker.
 *
 * <p>Every test builds its own driver, so the state machine of the speeding detection is
 * observed from a clean state and the configured speed limit can change per test.
 */
class TelemetryTopologyTest {

    private static final double SPEED_LIMIT = 120.0;
    private static final double CONFIGURED_SPEED_LIMIT = 90.0;
    private static final Instant FAILED_AT = Instant.parse("2026-09-21T09:16:00Z");
    private static final String LOCATION_TOPIC = "logistics.vehicle.location.v1";
    private static final String TRUCK = "TRUCK-00001";
    private static final String OTHER_TRUCK = "TRUCK-00002";

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
    void emitsExactlyOneAlertWhenAVehicleCrossesTheSpeedLimit() {
        Harness harness = harness(SPEED_LIMIT);

        harness.send(TRUCK, TelemetryFixtures.payload(TRUCK, 137.2));

        JsonNode alert = readAlert(harness, TRUCK);
        assertThat(alert.get("eventType").asText()).isEqualTo("SPEEDING_DETECTED");
        assertThat(alert.get("version").asInt()).isEqualTo(1);
        assertThat(alert.get("severity").asText()).isEqualTo("WARNING");
        assertThat(alert.get("vehicleId").asText()).isEqualTo(TRUCK);
        assertThat(alert.get("timestamp").asText()).isEqualTo(TelemetryFixtures.TIMESTAMP.toString());
        assertThat(UUID.fromString(alert.get("eventId").asText())).isNotNull();
        assertThat(alert.get("data").get("speed").asDouble()).isEqualTo(137.2);
        assertThat(alert.get("data").get("threshold").asDouble()).isEqualTo(SPEED_LIMIT);
        assertThat(harness.alerts().isEmpty()).isTrue();
        assertThat(harness.deadLetters().isEmpty()).isTrue();
    }

    @Test
    void doesNotRepeatTheAlertWhileTheVehicleStaysAboveTheLimit() {
        Harness harness = harness(SPEED_LIMIT);

        harness.send(TRUCK, TelemetryFixtures.payload(TRUCK, 137.2));
        harness.send(TRUCK, TelemetryFixtures.payload(TRUCK, 150.0));
        harness.send(TRUCK, TelemetryFixtures.payload(TRUCK, 120.5));

        assertThat(harness.alerts().readKeyValuesToList()).hasSize(1);
    }

    @Test
    void emitsASecondAlertAfterRecoveryAndANewCrossing() {
        Harness harness = harness(SPEED_LIMIT);
        harness.send(TRUCK, TelemetryFixtures.payload(TRUCK, 137.2));

        harness.send(TRUCK, TelemetryFixtures.payload(TRUCK, 80.0));

        assertThat(stateOf(harness).get(TRUCK)).isEqualTo("NORMAL");
        harness.send(TRUCK, TelemetryFixtures.payload(TRUCK, 130.0));

        List<KeyValue<String, String>> alerts = harness.alerts().readKeyValuesToList();
        assertThat(alerts).hasSize(2);
        assertThat(alerts.stream().map(alert -> alert.value).toList()).doesNotHaveDuplicates();
        assertThat(alerts).allSatisfy(alert -> assertThat(alert.key).isEqualTo(TRUCK));
        assertThat(stateOf(harness).get(TRUCK)).isEqualTo("SPEEDING");
    }

    @Test
    void keepsTheSpeedStatePerVehicle() {
        Harness harness = harness(SPEED_LIMIT);

        harness.send(TRUCK, TelemetryFixtures.payload(TRUCK, 90.0));
        harness.send(OTHER_TRUCK, TelemetryFixtures.payload(OTHER_TRUCK, 140.0));

        assertThat(readAlert(harness, OTHER_TRUCK).get("vehicleId").asText()).isEqualTo(OTHER_TRUCK);
        assertThat(harness.alerts().isEmpty()).isTrue();
        KeyValueStore<String, String> state = stateOf(harness);
        assertThat(VehicleSpeedState.from(state.get(TRUCK))).isEqualTo(VehicleSpeedState.NORMAL);
        assertThat(state.get(OTHER_TRUCK)).isEqualTo("SPEEDING");
    }

    @Test
    void doesNotAlertAtExactlyTheSpeedLimit() {
        Harness harness = harness(SPEED_LIMIT);

        harness.send(TRUCK, TelemetryFixtures.payload(TRUCK, SPEED_LIMIT));

        assertThat(harness.alerts().isEmpty()).isTrue();
        assertThat(VehicleSpeedState.from(stateOf(harness).get(TRUCK))).isEqualTo(VehicleSpeedState.NORMAL);
    }

    @Test
    void honoursTheConfiguredSpeedLimit() {
        Harness harness = harness(CONFIGURED_SPEED_LIMIT);

        harness.send(TRUCK, TelemetryFixtures.payload(TRUCK, 100.0));

        JsonNode alert = readAlert(harness, TRUCK);
        assertThat(alert.get("data").get("speed").asDouble()).isEqualTo(100.0);
        assertThat(alert.get("data").get("threshold").asDouble()).isEqualTo(CONFIGURED_SPEED_LIMIT);
    }

    @Test
    void routesMalformedJsonToTheDeadLetterTopic() {
        Harness harness = harness(SPEED_LIMIT);

        harness.send(TRUCK, "{not json");

        KeyValue<String, String> rejected = harness.deadLetters().readKeyValue();
        assertThat(rejected.key).isEqualTo(TRUCK);
        JsonNode envelope = read(rejected.value);
        assertThat(envelope.get("originalEvent").asText()).isEqualTo("{not json");
        assertThat(envelope.get("error").asText()).startsWith("malformed JSON payload");
        assertThat(envelope.get("failedAt").asText()).isEqualTo(FAILED_AT.toString());
        assertThat(envelope.get("sourceTopic").asText()).isEqualTo(LOCATION_TOPIC);
        assertThat(harness.alerts().isEmpty()).isTrue();
    }

    @Test
    void routesInvalidCoordinatesToTheDeadLetterTopic() {
        Harness harness = harness(SPEED_LIMIT);

        harness.send(TRUCK, TelemetryFixtures.payload(TRUCK, 80.0, 91.0, -0.3763));
        harness.send(TRUCK, TelemetryFixtures.payload(TRUCK, 80.0, 39.4699, 180.1));

        List<KeyValue<String, String>> rejected = harness.deadLetters().readKeyValuesToList();
        assertThat(rejected).hasSize(2);
        assertThat(read(rejected.get(0).value).get("error").asText()).contains("data.latitude");
        assertThat(read(rejected.get(1).value).get("error").asText()).contains("data.longitude");
        assertThat(harness.alerts().isEmpty()).isTrue();
    }

    @Test
    void routesInvalidVehicleIdsAndKeysToTheDeadLetterTopic() {
        Harness harness = harness(SPEED_LIMIT);

        harness.send(TRUCK, TelemetryFixtures.payload("  ", 80.0));
        harness.send("  ", TelemetryFixtures.payload(OTHER_TRUCK, 80.0));
        harness.send(OTHER_TRUCK, TelemetryFixtures.payload(TRUCK, 80.0));

        List<KeyValue<String, String>> rejected = harness.deadLetters().readKeyValuesToList();
        assertThat(rejected).hasSize(3);
        assertThat(read(rejected.get(0).value).get("error").asText()).contains("vehicleId");
        assertThat(read(rejected.get(1).value).get("error").asText()).contains("record key (vehicleId)");
        assertThat(read(rejected.get(2).value).get("error").asText()).contains("record key must match");
        assertThat(harness.alerts().isEmpty()).isTrue();
    }

    @Test
    void routesUnsupportedSchemaVersionsAndEventTypesToTheDeadLetterTopic() {
        Harness harness = harness(SPEED_LIMIT);

        harness.send(TRUCK, payloadWithVersion(2));
        harness.send(TRUCK, payloadWithEventType("VEHICLE_ENGINE_STARTED"));

        List<KeyValue<String, String>> rejected = harness.deadLetters().readKeyValuesToList();
        assertThat(rejected).hasSize(2);
        assertThat(read(rejected.get(0).value).get("error").asText()).contains("unsupported event version: 2");
        assertThat(read(rejected.get(1).value).get("error").asText())
                .contains("unsupported eventType: VEHICLE_ENGINE_STARTED");
        assertThat(harness.alerts().isEmpty()).isTrue();
    }

    @Test
    void continuesProcessingValidEventsAfterAnInvalidOne() {
        Harness harness = harness(SPEED_LIMIT);

        harness.send(TRUCK, "{not json");
        harness.send(TRUCK, TelemetryFixtures.payload(TRUCK, 137.2));
        harness.send(OTHER_TRUCK, TelemetryFixtures.payload(OTHER_TRUCK, 60.0));

        assertThat(harness.deadLetters().readKeyValuesToList()).hasSize(1);
        assertThat(harness.alerts().readKeyValuesToList()).hasSize(1);
        assertThat(VehicleSpeedState.from(stateOf(harness).get(OTHER_TRUCK))).isEqualTo(VehicleSpeedState.NORMAL);
    }

    private Harness harness(double speedLimitKph) {
        ProcessingProperties processing = new ProcessingProperties();
        processing.setSpeedLimitKph(speedLimitKph);
        KafkaTopicsProperties topics = new KafkaTopicsProperties();
        Clock clock = Clock.fixed(FAILED_AT, ZoneOffset.UTC);

        StreamsBuilder builder = new StreamsBuilder();
        new TelemetryTopologyConfiguration()
                .telemetryProcessingTopology(
                        builder,
                        objectMapper,
                        TelemetryFixtures.validator(),
                        processing,
                        topics,
                        clock,
                        new RecordingPersistence());

        TopologyTestDriver driver = new TopologyTestDriver(builder.build(), streamsConfiguration());
        drivers.add(driver);

        return new Harness(
                driver,
                driver.createInputTopic(topics.locationTopic(), new StringSerializer(), new StringSerializer()),
                driver.createOutputTopic(topics.alertTopic(), new StringDeserializer(), new StringDeserializer()),
                driver.createOutputTopic(topics.deadLetterTopic(), new StringDeserializer(), new StringDeserializer()));
    }

    private static Properties streamsConfiguration() {
        Properties properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "stream-processor-test");
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        return properties;
    }

    private static KeyValueStore<String, String> stateOf(Harness harness) {
        return harness.driver().getKeyValueStore(SpeedingAlertProcessor.STATE_STORE_NAME);
    }

    private JsonNode readAlert(Harness harness, String expectedVehicleId) {
        KeyValue<String, String> alert = harness.alerts().readKeyValue();
        assertThat(alert.key).isEqualTo(expectedVehicleId);
        return read(alert.value);
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception failure) {
            throw new IllegalStateException("reading " + json + " failed", failure);
        }
    }

    private static String payloadWithVersion(int version) {
        return payloadWith("version", version);
    }

    private static String payloadWithEventType(String eventType) {
        return payloadWith("eventType", eventType);
    }

    private static String payloadWith(String field, Object value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("latitude", 39.4699);
        data.put("longitude", -0.3763);
        data.put("speed", 80.0);
        data.put("heading", 214.5);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("eventType", "VEHICLE_LOCATION_UPDATED");
        payload.put("version", 1);
        payload.put("vehicleId", TRUCK);
        payload.put("timestamp", TelemetryFixtures.TIMESTAMP.toString());
        payload.put("data", data);
        payload.put(field, value);

        return TelemetryFixtures.serialize(payload);
    }

    private record Harness(
            TopologyTestDriver driver,
            TestInputTopic<String, String> telemetry,
            TestOutputTopic<String, String> alerts,
            TestOutputTopic<String, String> deadLetters) {

        void send(String vehicleId, String payload) {
            telemetry.pipeInput(vehicleId, payload, TelemetryFixtures.TIMESTAMP);
        }
    }
}

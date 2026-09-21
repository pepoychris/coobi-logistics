package com.coobi.logistics.streamprocessor.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.coobi.logistics.streamprocessor.config.KafkaTopicsProperties;
import com.coobi.logistics.streamprocessor.config.ProcessingProperties;
import com.coobi.logistics.streamprocessor.support.TelemetryFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
 * MVP-3.1 and MVP-3.2: the per-vehicle state store and the stopped-vehicle detection,
 * driven through the wired topology without a broker.
 *
 * <p>Every timeline is expressed with the timestamps of the telemetry itself, so a
 * five-minute window is covered without a single wait. The Kafka record timestamp equals
 * the event timestamp, exactly as the generator publishes it.
 */
class VehicleStateTopologyTest {

    private static final double SPEED_LIMIT = 120.0;
    private static final long DEFAULT_STOPPED_WINDOW_SECONDS = 300;
    private static final double DEFAULT_MOVEMENT_THRESHOLD_METERS = 50.0;

    private static final Instant START = Instant.parse("2026-09-21T09:15:00Z");
    private static final Instant FAILED_AT = Instant.parse("2026-09-21T09:16:00Z");
    private static final String TRUCK = "TRUCK-00001";
    private static final String OTHER_TRUCK = "TRUCK-00002";

    private static final double LATITUDE = TelemetryFixtures.DEMO_LATITUDE;
    private static final double LONGITUDE = TelemetryFixtures.DEMO_LONGITUDE;

    /** 20.02 m north of the demonstration position. */
    private static final double TWENTY_METERS_NORTH = 39.47008;

    /** 40.03 m north of the demonstration position. */
    private static final double FORTY_METERS_NORTH = 39.47026;

    /** 122.31 m north of the demonstration position. */
    private static final double ONE_HUNDRED_TWENTY_METERS_NORTH = 39.471;

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
    void reconstructsTheLatestStateOfAVehicleFromTheStore() {
        Harness harness = harness();

        harness.send(TRUCK, START, 80.0, LATITUDE, LONGITUDE);

        JsonNode first = storedState(harness, TRUCK);
        assertThat(first.get("latitude").asDouble()).isEqualTo(LATITUDE);
        assertThat(first.get("longitude").asDouble()).isEqualTo(LONGITUDE);
        assertThat(first.get("speed").asDouble()).isEqualTo(80.0);
        assertThat(first.get("heading").asDouble()).isEqualTo(TelemetryFixtures.DEMO_HEADING);
        assertThat(first.get("lastUpdate").asText()).isEqualTo(START.toString());
        assertThat(first.get("status").asText()).isEqualTo("MOVING");
        assertThat(first.get("windowStartedAt").asText()).isEqualTo(START.toString());
        assertThat(first.get("accumulatedMeters").asDouble()).isEqualTo(0.0);
        assertThat(harness.alerts().isEmpty()).isTrue();

        harness.send(TRUCK, START.plusSeconds(30), 95.5, ONE_HUNDRED_TWENTY_METERS_NORTH, LONGITUDE);

        JsonNode latest = storedState(harness, TRUCK);
        assertThat(latest.get("latitude").asDouble()).isEqualTo(ONE_HUNDRED_TWENTY_METERS_NORTH);
        assertThat(latest.get("speed").asDouble()).isEqualTo(95.5);
        assertThat(latest.get("lastUpdate").asText()).isEqualTo(START.plusSeconds(30).toString());
        assertThat(latest.get("status").asText()).isEqualTo("MOVING");
        assertThat(harness.alerts().isEmpty()).isTrue();
        assertThat(harness.deadLetters().isEmpty()).isTrue();
    }

    @Test
    void keepsTheStateOfEachVehicleUnderItsOwnKey() {
        Harness harness = harness();

        harness.send(TRUCK, START, 60.0, LATITUDE, LONGITUDE);
        harness.send(OTHER_TRUCK, START, 140.0, LATITUDE, LONGITUDE);
        harness.send(TRUCK, START.plusSeconds(30), 65.0, TWENTY_METERS_NORTH, LONGITUDE);

        assertThat(storedState(harness, TRUCK).get("speed").asDouble()).isEqualTo(65.0);
        assertThat(storedState(harness, TRUCK).get("heading").asDouble())
                .isEqualTo(TelemetryFixtures.DEMO_HEADING);
        assertThat(storedState(harness, OTHER_TRUCK).get("speed").asDouble()).isEqualTo(140.0);

        List<KeyValue<String, String>> alerts = harness.alerts().readKeyValuesToList();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).key).isEqualTo(OTHER_TRUCK);
        assertThat(read(alerts.get(0).value).get("eventType").asText()).isEqualTo("SPEEDING_DETECTED");
    }

    @Test
    void emitsOneStoppedAlertOnceTheWindowElapsedWithoutMovement() {
        Harness harness = harness();

        harness.send(TRUCK, START, 0.0, LATITUDE, LONGITUDE);
        harness.send(TRUCK, START.plusSeconds(60), 0.0, LATITUDE, LONGITUDE);
        harness.send(TRUCK, START.plusSeconds(120), 0.0, LATITUDE, LONGITUDE);
        harness.send(TRUCK, START.plusSeconds(180), 0.0, LATITUDE, LONGITUDE);
        harness.send(TRUCK, START.plusSeconds(240), 0.0, LATITUDE, LONGITUDE);

        assertThat(harness.alerts().isEmpty())
                .as("no alert before the window has elapsed")
                .isTrue();
        assertThat(storedState(harness, TRUCK).get("status").asText()).isEqualTo("MOVING");

        harness.send(TRUCK, START.plusSeconds(300), 0.0, LATITUDE, LONGITUDE);

        KeyValue<String, String> alert = harness.alerts().readKeyValue();
        assertThat(alert.key).isEqualTo(TRUCK);
        JsonNode document = read(alert.value);
        assertThat(document.get("eventType").asText()).isEqualTo("VEHICLE_STOPPED_DETECTED");
        assertThat(document.get("version").asInt()).isEqualTo(1);
        assertThat(document.get("severity").asText()).isEqualTo("WARNING");
        assertThat(document.get("vehicleId").asText()).isEqualTo(TRUCK);
        assertThat(document.get("timestamp").asText()).isEqualTo(START.plusSeconds(300).toString());
        assertThat(UUID.fromString(document.get("eventId").asText())).isNotNull();
        assertThat(document.get("data").get("speed").asDouble()).isEqualTo(0.0);
        assertThat(document.get("data").get("threshold").asDouble())
                .isEqualTo(DEFAULT_MOVEMENT_THRESHOLD_METERS);
        assertThat(storedState(harness, TRUCK).get("status").asText()).isEqualTo("STOPPED");
        assertThat(harness.deadLetters().isEmpty()).isTrue();
    }

    @Test
    void doesNotRepeatTheAlertWhileTheVehicleStaysStopped() {
        Harness harness = parkedUntilStopped();
        assertThat(harness.alerts().readKeyValuesToList()).hasSize(1);

        harness.send(TRUCK, START.plusSeconds(600), 0.0, LATITUDE, LONGITUDE);
        harness.send(TRUCK, START.plusSeconds(900), 0.0, LATITUDE, LONGITUDE);

        assertThat(harness.alerts().isEmpty()).isTrue();
        JsonNode state = storedState(harness, TRUCK);
        assertThat(state.get("status").asText()).isEqualTo("STOPPED");
        assertThat(state.get("lastUpdate").asText()).isEqualTo(START.plusSeconds(900).toString());
        assertThat(state.get("windowStartedAt").asText()).isEqualTo(START.toString());
    }

    @Test
    void resetsTheStoppedStatusWhenTheVehicleMovesAgain() {
        Harness harness = parkedUntilStopped();
        assertThat(harness.alerts().readKeyValuesToList()).hasSize(1);

        Instant departure = START.plusSeconds(330);
        harness.send(TRUCK, departure, 25.0, ONE_HUNDRED_TWENTY_METERS_NORTH, LONGITUDE);

        JsonNode state = storedState(harness, TRUCK);
        assertThat(state.get("status").asText()).isEqualTo("MOVING");
        assertThat(state.get("windowStartedAt").asText()).isEqualTo(departure.toString());
        assertThat(state.get("accumulatedMeters").asDouble()).isEqualTo(0.0);
        assertThat(state.get("speed").asDouble()).isEqualTo(25.0);
        assertThat(harness.alerts().isEmpty()).as("driving away is not an alert").isTrue();
    }

    @Test
    void emitsASecondStoppedAlertAfterANewStationaryWindow() {
        Harness harness = parkedUntilStopped();
        harness.alerts().readKeyValuesToList();

        Instant departure = START.plusSeconds(330);
        harness.send(TRUCK, departure, 25.0, ONE_HUNDRED_TWENTY_METERS_NORTH, LONGITUDE);
        harness.send(TRUCK, departure.plusSeconds(60), 0.0, ONE_HUNDRED_TWENTY_METERS_NORTH, LONGITUDE);
        harness.send(TRUCK, departure.plusSeconds(120), 0.0, ONE_HUNDRED_TWENTY_METERS_NORTH, LONGITUDE);

        assertThat(harness.alerts().isEmpty()).isTrue();

        harness.send(TRUCK, departure.plusSeconds(300), 0.0, ONE_HUNDRED_TWENTY_METERS_NORTH, LONGITUDE);

        KeyValue<String, String> alert = harness.alerts().readKeyValue();
        assertThat(alert.key).isEqualTo(TRUCK);
        JsonNode document = read(alert.value);
        assertThat(document.get("eventType").asText()).isEqualTo("VEHICLE_STOPPED_DETECTED");
        assertThat(document.get("timestamp").asText()).isEqualTo(departure.plusSeconds(300).toString());
    }

    @Test
    void countsMovementBelowTheThresholdAsStopped() {
        Harness harness = harness();

        harness.send(TRUCK, START, 0.0, LATITUDE, LONGITUDE);
        harness.send(TRUCK, START.plusSeconds(60), 0.5, TWENTY_METERS_NORTH, LONGITUDE);
        harness.send(TRUCK, START.plusSeconds(120), 0.5, FORTY_METERS_NORTH, LONGITUDE);
        harness.send(TRUCK, START.plusSeconds(300), 0.5, FORTY_METERS_NORTH, LONGITUDE);

        JsonNode state = storedState(harness, TRUCK);
        assertThat(state.get("status").asText()).isEqualTo("STOPPED");
        assertThat(state.get("accumulatedMeters").asDouble()).isCloseTo(40.03, within(0.1));
        assertThat(read(harness.alerts().readKeyValue().value).get("eventType").asText())
                .isEqualTo("VEHICLE_STOPPED_DETECTED");
    }

    @Test
    void honoursTheConfiguredWindowAndMovementThreshold() {
        Harness harness = harness(60, 200.0);

        harness.send(TRUCK, START, 0.0, LATITUDE, LONGITUDE);
        harness.send(TRUCK, START.plusSeconds(60), 0.0, ONE_HUNDRED_TWENTY_METERS_NORTH, LONGITUDE);

        // The same telemetry does not alert under the shipped defaults: the window is only a
        // minute wide and the 122 m step stays below the configured 200 m of movement.
        JsonNode document = read(harness.alerts().readKeyValue().value);
        assertThat(document.get("eventType").asText()).isEqualTo("VEHICLE_STOPPED_DETECTED");
        assertThat(document.get("timestamp").asText()).isEqualTo(START.plusSeconds(60).toString());
        assertThat(document.get("data").get("threshold").asDouble()).isEqualTo(200.0);
        assertThat(storedState(harness, TRUCK).get("accumulatedMeters").asDouble())
                .isCloseTo(122.31, within(0.1));
    }

    @Test
    void emitsSpeedingAndStoppedAlertsThroughTheSameTopic() {
        Harness harness = harness();

        harness.send(TRUCK, START, 140.0, LATITUDE, LONGITUDE);
        harness.send(TRUCK, START.plusSeconds(10), 0.0, LATITUDE, LONGITUDE);
        harness.send(TRUCK, START.plusSeconds(300), 0.0, LATITUDE, LONGITUDE);

        List<String> eventTypes = harness.alerts().readKeyValuesToList().stream()
                .map(alert -> read(alert.value).get("eventType").asText())
                .toList();
        assertThat(eventTypes).containsExactly("SPEEDING_DETECTED", "VEHICLE_STOPPED_DETECTED");
    }

    @Test
    void routesInvalidTelemetryToTheDeadLetterTopicWithoutWritingState() {
        Harness harness = harness();

        harness.send(TRUCK, START, 0.0, LATITUDE, LONGITUDE);
        harness.send(TRUCK, START.plusSeconds(10), 0.0, 91.0, LONGITUDE);

        assertThat(read(harness.deadLetters().readKeyValue().value).get("error").asText())
                .contains("data.latitude");
        assertThat(harness.alerts().isEmpty()).isTrue();
        JsonNode state = storedState(harness, TRUCK);
        assertThat(state.get("lastUpdate").asText())
                .as("the rejected record never reaches the state store")
                .isEqualTo(START.toString());
    }

    @Test
    void keepsBothStateStoresChangelogBackedSoTheirStateSurvivesARestart() {
        assertThat(SpeedingAlertProcessor.stateStore().name()).isEqualTo(SpeedingAlertProcessor.STATE_STORE_NAME);
        assertThat(SpeedingAlertProcessor.stateStore().loggingEnabled()).isTrue();
        assertThat(VehicleStateProcessor.stateStore().name()).isEqualTo(VehicleStateProcessor.STATE_STORE_NAME);
        assertThat(VehicleStateProcessor.stateStore().loggingEnabled()).isTrue();
    }

    /** A vehicle that stands still from {@code START} until the default window has elapsed. */
    private Harness parkedUntilStopped() {
        Harness harness = harness();
        for (long elapsed = 0; elapsed <= DEFAULT_STOPPED_WINDOW_SECONDS; elapsed += 60) {
            harness.send(TRUCK, START.plusSeconds(elapsed), 0.0, LATITUDE, LONGITUDE);
        }
        return harness;
    }

    private Harness harness() {
        return harness(DEFAULT_STOPPED_WINDOW_SECONDS, DEFAULT_MOVEMENT_THRESHOLD_METERS);
    }

    private Harness harness(long stoppedWindowSeconds, double movementThresholdMeters) {
        ProcessingProperties processing = new ProcessingProperties();
        processing.setSpeedLimitKph(SPEED_LIMIT);
        processing.setStoppedWindowSeconds(stoppedWindowSeconds);
        processing.setMovementThresholdMeters(movementThresholdMeters);
        KafkaTopicsProperties topics = new KafkaTopicsProperties();
        Clock clock = Clock.fixed(FAILED_AT, ZoneOffset.UTC);

        StreamsBuilder builder = new StreamsBuilder();
        new TelemetryTopologyConfiguration()
                .telemetryProcessingTopology(
                        builder, objectMapper, TelemetryFixtures.validator(), processing, topics, clock);

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
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "stream-processor-vehicle-state-test");
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        return properties;
    }

    private static JsonNode storedState(Harness harness, String vehicleId) {
        String stored = harness.state().get(vehicleId);
        assertThat(stored).as("state of %s", vehicleId).isNotNull();
        return read(stored);
    }

    private static JsonNode read(String json) {
        try {
            return TelemetryFixtures.objectMapper().readTree(json);
        } catch (Exception failure) {
            throw new IllegalStateException("reading " + json + " failed", failure);
        }
    }

    private record Harness(
            TopologyTestDriver driver,
            TestInputTopic<String, String> telemetry,
            TestOutputTopic<String, String> alerts,
            TestOutputTopic<String, String> deadLetters) {

        void send(String vehicleId, Instant timestamp, double speed, double latitude, double longitude) {
            telemetry.pipeInput(
                    vehicleId, TelemetryFixtures.payload(vehicleId, timestamp, speed, latitude, longitude), timestamp);
        }

        KeyValueStore<String, String> state() {
            return driver.getKeyValueStore(VehicleStateProcessor.STATE_STORE_NAME);
        }
    }
}

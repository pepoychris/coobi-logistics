package com.coobi.logistics.streamprocessor.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.streamprocessor.config.KafkaTopicsProperties;
import com.coobi.logistics.streamprocessor.config.ProcessingProperties;
import com.coobi.logistics.streamprocessor.support.RecordingPersistence;
import com.coobi.logistics.streamprocessor.support.TelemetryFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * MVP-8.1: the metrics of the pipeline are produced by the topology, not by the framework.
 *
 * <p>The topology is driven with a real {@link PrometheusMeterRegistry}, so every assertion is
 * made on a value that a Prometheus scrape would read: the counters are compared with the
 * number of records that took each path, and the exposition format is checked for the exact
 * metric names, types and values of the contract in {@code docs/observability.md}.
 */
class LogisticsMetricsTopologyTest {

    private static final double SPEED_LIMIT = 120.0;
    private static final Instant START = Instant.parse("2026-09-21T09:15:00Z");
    private static final Instant FAILED_AT = Instant.parse("2026-09-21T09:16:00Z");
    private static final String TRUCK = "TRUCK-00001";
    private static final String OTHER_TRUCK = "TRUCK-00002";
    private static final double LATITUDE = TelemetryFixtures.DEMO_LATITUDE;
    private static final double LONGITUDE = TelemetryFixtures.DEMO_LONGITUDE;

    /**
     * The exposition name of the duration summary. Micrometer registers the meter as
     * {@link TelemetryMetrics#PROCESSING_DURATION_METRIC} and renders it in the Prometheus
     * format with the base unit in the name, which is the convention for durations.
     */
    private static final String DURATION_COUNT = TelemetryMetrics.PROCESSING_DURATION_METRIC + "_seconds_count";

    private static final String DURATION_SUM = TelemetryMetrics.PROCESSING_DURATION_METRIC + "_seconds_sum";

    private final ObjectMapper objectMapper = TelemetryFixtures.objectMapper();
    private final PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final TelemetryMetrics metrics = new TelemetryMetrics(meterRegistry);
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
    void countsEveryRecordOnThePathItTook() {
        Harness harness = harness();

        harness.send(TRUCK, START, 80.0);
        harness.send(OTHER_TRUCK, START, 60.0);
        harness.send(OTHER_TRUCK, START, "{not json");

        assertThat(value(TelemetryMetrics.RECEIVED_METRIC)).isEqualTo(3);
        assertThat(value(TelemetryMetrics.PROCESSED_METRIC)).isEqualTo(2);
        assertThat(value(TelemetryMetrics.FAILED_METRIC)).isEqualTo(1);
        // Received is the record count of the topic, and the two branches never overlap.
        assertThat(value(TelemetryMetrics.PROCESSED_METRIC) + value(TelemetryMetrics.FAILED_METRIC))
                .isEqualTo(value(TelemetryMetrics.RECEIVED_METRIC));
    }

    @Test
    void countsGeneratedAlertsPerDetectionType() {
        Harness harness = harness();

        harness.send(TRUCK, START, 137.2);
        harness.send(TRUCK, START, 80.0);
        harness.send(TRUCK, START, 150.0);
        harness.send(OTHER_TRUCK, START, 60.0);
        harness.send(OTHER_TRUCK, START.plusSeconds(300), 0.0);

        assertThat(harness.alerts().readKeyValuesToList()).hasSize(3);
        assertThat(value(TelemetryMetrics.ALERTS_METRIC, "type=\"SPEEDING\"")).isEqualTo(2);
        assertThat(value(TelemetryMetrics.ALERTS_METRIC, "type=\"VEHICLE_STOPPED\"")).isEqualTo(1);
    }

    @Test
    void timesEveryProcessingStepOfTheTopology() {
        Harness harness = harness();

        harness.send(TRUCK, START, 80.0);
        harness.send(OTHER_TRUCK, START, "{not json");

        // Five steps: validation of both records, the speeding detection and the vehicle-state
        // detection of the accepted one, and the dead letter mapping of the rejected one.
        assertThat(value(DURATION_COUNT)).isEqualTo(5);
        assertThat(value(DURATION_SUM)).isGreaterThan(0);
    }

    @Test
    void rendersTheContractInThePrometheusExpositionFormat() {
        Harness harness = harness();

        harness.send(TRUCK, START, 137.2);
        harness.send(TRUCK, START, "{not json");

        String scrape = meterRegistry.scrape();
        assertThat(scrape)
                .contains("# HELP " + TelemetryMetrics.RECEIVED_METRIC + " ")
                .contains("# TYPE " + TelemetryMetrics.RECEIVED_METRIC + " counter")
                .contains("# TYPE " + TelemetryMetrics.PROCESSED_METRIC + " counter")
                .contains("# TYPE " + TelemetryMetrics.FAILED_METRIC + " counter")
                .contains("# TYPE " + TelemetryMetrics.ALERTS_METRIC + " counter")
                .contains("# TYPE " + TelemetryMetrics.PROCESSING_DURATION_METRIC + "_seconds summary")
                .contains(TelemetryMetrics.ALERTS_METRIC + "{type=\"SPEEDING\"}");
        assertThat(value(TelemetryMetrics.RECEIVED_METRIC)).isEqualTo(2);
        assertThat(value(TelemetryMetrics.PROCESSED_METRIC)).isEqualTo(1);
        assertThat(value(TelemetryMetrics.FAILED_METRIC)).isEqualTo(1);
        assertThat(value(TelemetryMetrics.ALERTS_METRIC, "type=\"SPEEDING\"")).isEqualTo(1);
        assertThat(value(DURATION_COUNT)).isEqualTo(5);
    }

    /**
     * One value of the scrape output, matched by metric name and by the labels the caller
     * cares about. Reading the value the way Prometheus does keeps the assertion honest: the
     * test can only pass for a series the exposition format really carries.
     */
    private double value(String metricName, String... labels) {
        String scrape = meterRegistry.scrape();
        return scrape.lines()
                .filter(line -> !line.startsWith("#"))
                .filter(line -> metricNameOf(line).equals(metricName))
                .filter(line -> List.of(labels).stream().allMatch(line::contains))
                .mapToDouble(LogisticsMetricsTopologyTest::valueOf)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        metricName + " " + String.join(" ", labels) + " is absent from:\n" + scrape));
    }

    private static String metricNameOf(String sampleLine) {
        int labelsStart = sampleLine.indexOf('{');
        int nameEnd = labelsStart < 0 ? sampleLine.indexOf(' ') : labelsStart;
        return sampleLine.substring(0, nameEnd);
    }

    private static double valueOf(String sampleLine) {
        return Double.parseDouble(sampleLine.substring(sampleLine.lastIndexOf(' ') + 1));
    }

    private Harness harness() {
        ProcessingProperties processing = new ProcessingProperties();
        processing.setSpeedLimitKph(SPEED_LIMIT);
        KafkaTopicsProperties topics = new KafkaTopicsProperties();

        StreamsBuilder builder = new StreamsBuilder();
        new TelemetryTopologyConfiguration()
                .telemetryProcessingTopology(
                        builder,
                        objectMapper,
                        TelemetryFixtures.validator(),
                        processing,
                        topics,
                        Clock.fixed(FAILED_AT, ZoneOffset.UTC),
                        new RecordingPersistence(),
                        metrics);

        TopologyTestDriver driver = new TopologyTestDriver(builder.build(), streamsConfiguration());
        drivers.add(driver);

        return new Harness(
                driver.createInputTopic(topics.locationTopic(), new StringSerializer(), new StringSerializer()),
                driver.createOutputTopic(topics.alertTopic(), new StringDeserializer(), new StringDeserializer()));
    }

    private static Properties streamsConfiguration() {
        Properties properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "stream-processor-metrics-test");
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        return properties;
    }

    private record Harness(TestInputTopic<String, String> telemetry, TestOutputTopic<String, String> alerts) {

        void send(String vehicleId, Instant timestamp, Object speedOrPayload) {
            if (speedOrPayload instanceof String payload) {
                telemetry.pipeInput(vehicleId, payload, timestamp);
                return;
            }
            telemetry.pipeInput(
                    vehicleId,
                    TelemetryFixtures.payload(vehicleId, timestamp, (Double) speedOrPayload, LATITUDE, LONGITUDE),
                    timestamp);
        }
    }
}

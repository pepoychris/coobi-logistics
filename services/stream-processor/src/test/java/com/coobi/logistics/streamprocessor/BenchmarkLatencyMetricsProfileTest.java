package com.coobi.logistics.streamprocessor;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.streamprocessor.processing.TelemetryMetrics;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * MVP-11.1: the benchmark profile publishes the latency distribution the report needs.
 *
 * <p>The profile is the only difference between this context and the default one of
 * {@link StreamProcessorApplicationTests}: the topology, the detections and the three counters
 * are the same, and nothing is started against a broker or a database - the topology is built
 * but not started, and the persistence layer points at an in-memory database. What is asserted
 * here is that the profile adds the cumulative histogram of the processing timer, which is what
 * makes a p50/p95/p99 in {@code docs/benchmarks.md} a measured number rather than an estimate.
 */
@ActiveProfiles("benchmark")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.streams.auto-startup=false",
            "coobi.kafka.initialization.enabled=false",
            "management.health.kafka.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:stream-processor-benchmark-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.enabled=false"
        })
class BenchmarkLatencyMetricsProfileTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TelemetryMetrics telemetryMetrics;

    @Test
    void publishesTheHistogramOfTheProcessingTimer() {
        // A percentile of nothing is not a percentile, so the timer is given real observations
        // first: three steps of one second, one of two and one of five seconds.
        for (Duration step : new Duration[] {
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
            Duration.ofSeconds(2), Duration.ofSeconds(5)
        }) {
            telemetryMetrics.processingDuration().record(step);
        }

        ResponseEntity<String> prometheus = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = prometheus.getBody();
        assertThat(body)
                // The timer is exported as a histogram rather than as a summary ...
                .contains("# TYPE logistics_event_processing_duration_seconds histogram")
                // ... with the five observations that were recorded in it ...
                .contains("logistics_event_processing_duration_seconds_count{application=\"stream-processor\"} 5")
                .contains("logistics_event_processing_duration_seconds_sum{application=\"stream-processor\"} 10.0")
                // ... with the boundaries of the profile, the sub-millisecond ones included,
                // which the default grid of Micrometer does not reach ...
                .contains("le=\"5.0E-5\"")
                .contains("le=\"1.0E-4\"")
                .contains("le=\"1.0\"")
                // ... and with the open bucket a percentile above every bound needs.
                .contains("le=\"+Inf\"");
        // One bucket per configured boundary at least, so the profile really added its own
        // boundaries instead of leaving the default set alone. The count is asserted rather
        // than the rendered label values, whose formatting belongs to the registry.
        assertThat(Pattern.compile("logistics_event_processing_duration_seconds_bucket\\{")
                        .matcher(body)
                        .results()
                        .count())
                .isGreaterThanOrEqualTo(15);
    }

    /** The profile is opt-in: the rest of the contract is the one of the default profile. */
    @Test
    void keepsTheContractMetricsOfTheDefaultProfile() {
        ResponseEntity<String> prometheus = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(prometheus.getBody())
                .contains("# TYPE logistics_events_received_total counter")
                .contains("# TYPE logistics_events_processed_total counter")
                .contains("# TYPE logistics_events_failed_total counter")
                .contains("application=\"stream-processor\"");
    }
}

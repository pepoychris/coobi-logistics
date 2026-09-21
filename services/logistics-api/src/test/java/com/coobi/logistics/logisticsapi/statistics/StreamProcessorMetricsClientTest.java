package com.coobi.logistics.logisticsapi.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.coobi.logistics.logisticsapi.config.StatisticsProperties;
import com.coobi.logistics.logisticsapi.support.AdvanceableClock;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * MVP-5.4: how the processed-event counter of the stream processor is read, and what the
 * client reports when there is nothing honest to report.
 *
 * <p>The processor is simulated at the HTTP level, so the tests cover the parts that matter
 * in production: which URL is called, which measurements make up the counter, that two
 * readings are needed for a rate, and that an unreachable processor is a {@code null} value
 * rather than a failed request.
 */
class StreamProcessorMetricsClientTest {

    private static final String METRIC = "logistics_events_processed_total";
    private static final Instant STARTED_AT = Instant.parse("2026-09-21T09:00:00Z");

    private final AdvanceableClock clock = new AdvanceableClock(STARTED_AT);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final StreamProcessorMetricsClient client =
            new StreamProcessorMetricsClient(properties(), builder.build(), clock);

    @Test
    void sumsTheCounterOfEveryStreamThread() {
        respondWithCounter(9_000_000.0, 3_938_281.0);

        Optional<StreamProcessorMetrics.ProcessedEvents> read = client.read();

        assertThat(read).isPresent();
        assertThat(read.get().total()).isEqualTo(12_938_281L);
        assertThat(read.get().perSecond())
                .as("the first reading of an instance has no rate to report")
                .isNull();
        server.verify();
    }

    @Test
    void derivesTheRateBetweenTwoReadings() {
        respondWithCounter(900.0);
        assertThat(client.read().orElseThrow().perSecond()).isNull();
        server.verify();

        clock.advance(Duration.ofSeconds(2));
        respondWithCounter(21_100.0);

        StreamProcessorMetrics.ProcessedEvents second = client.read().orElseThrow();

        assertThat(second.total()).isEqualTo(21_100L);
        assertThat(second.perSecond())
                .as("(21100 - 900) events over 2 seconds")
                .isEqualTo(10_100L);
        server.verify();
    }

    @Test
    void averagesOverTheWindowWhenAReadingFailsInBetween() {
        respondWithCounter(1_000.0);
        assertThat(client.read()).isPresent();
        server.verify();

        clock.advance(Duration.ofSeconds(1));
        respondWithFailure();
        assertThat(client.read()).isEmpty();
        server.verify();

        clock.advance(Duration.ofSeconds(1));
        respondWithCounter(3_000.0);

        StreamProcessorMetrics.ProcessedEvents afterTheOutage = client.read().orElseThrow();

        assertThat(afterTheOutage.perSecond())
                .as("the rate averages the whole window, outage included")
                .isEqualTo(1_000L);
        server.verify();
    }

    @Test
    void reportsNoCounterWhenTheMetricIsNotPublished() {
        respondWithStatus(HttpStatus.NOT_FOUND);

        assertThat(client.read()).isEmpty();
        server.verify();
    }

    @Test
    void reportsNoCounterWhenTheProcessorIsUnreachable() {
        respondWithFailure();

        assertThat(client.read()).isEmpty();
        server.verify();
    }

    @Test
    void reportsNoRateWhenTheCounterRestartedWithTheProcessor() {
        respondWithCounter(1_000.0);
        assertThat(client.read().orElseThrow().total()).isEqualTo(1_000L);
        server.verify();

        clock.advance(Duration.ofSeconds(5));
        // A processor that restarted starts counting from zero again: the delta is not a rate.
        respondWithCounter(400.0);

        StreamProcessorMetrics.ProcessedEvents afterRestart = client.read().orElseThrow();

        assertThat(afterRestart.total()).isEqualTo(400L);
        assertThat(afterRestart.perSecond()).isNull();
        server.verify();
    }

    @Test
    void completesTheCounterAgainAfterTheProcessorAnswersOnceMore() {
        respondWithStatus(HttpStatus.NOT_FOUND);
        assertThat(client.read()).isEmpty();
        server.verify();

        respondWithCounter(7.0);

        assertThat(client.read().orElseThrow().total()).isEqualTo(7L);
        server.verify();
    }

    /**
     * Registers the next answer of the processor.
     *
     * <p>The expectations of a mock request server are registered before the requests they
     * answer, so a test with several readings resets the server between them; the verification
     * of each answer happens right after the reading that consumes it, which keeps the URL
     * this client calls part of the test.
     */
    private void respondWithCounter(double... counts) {
        server.reset();
        server.expect(requestTo(metricsUrl()))
                .andRespond(withSuccess(counter(counts), MediaType.APPLICATION_JSON));
    }

    private void respondWithStatus(HttpStatus status) {
        server.reset();
        server.expect(requestTo(metricsUrl())).andRespond(withStatus(status));
    }

    private void respondWithFailure() {
        server.reset();
        server.expect(requestTo(metricsUrl())).andRespond(withException(new IOException("connection refused")));
    }

    private static String metricsUrl() {
        return "http://processor.test:8081/actuator/metrics/" + METRIC;
    }

    private static String counter(double... counts) {
        StringBuilder measurements = new StringBuilder();
        for (double count : counts) {
            measurements.append(measurements.isEmpty() ? "" : ",")
                    .append("{\"statistic\":\"COUNT\",\"value\":").append(count).append("}");
        }
        // Actuator also publishes measurements that are not the counter, and tags that the
        // client does not read: both are part of the real response.
        return "{\"name\":\"" + METRIC + "\",\"description\":\"The total number of records processed\","
                + "\"baseUnit\":\"events\",\"measurements\":[" + measurements
                + ",{\"statistic\":\"TOTAL_TIME\",\"value\":12.5}],"
                + "\"availableTags\":[{\"tag\":\"thread-id\",\"values\":[\"1\",\"2\"]}]}";
    }

    private static StatisticsProperties properties() {
        return new StatisticsProperties(new StatisticsProperties.StreamProcessor(
                URI.create("http://processor.test:8081/actuator/metrics"),
                METRIC,
                Duration.ofSeconds(2)));
    }
}

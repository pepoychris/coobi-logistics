package com.coobi.logistics.logisticsapi.statistics;

import com.coobi.logistics.logisticsapi.config.StatisticsProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Reads the processed-event counter of the stream processor over the Actuator metrics
 * endpoint (MVP-5.4).
 *
 * <p>The number is not derived from the database, because the database deliberately keeps one
 * state row per vehicle and no event history (MVP-4): the only place the count of processed
 * events exists is the Kafka Streams metric of the processor. Two consequences follow, and
 * both are visible in the response rather than hidden:
 *
 * <ul>
 *   <li>when the processor is unreachable, or does not publish the metric, the endpoint
 *       reports {@code null} - a number that looked live but was not would be worse;
 *   <li>the rate is derived from two consecutive readings of this instance, so the first
 *       response after a start has no rate and reports {@code null} as well.
 * </ul>
 *
 * <p>The read is bounded by the configured timeout, so a processor that hangs cannot hold a
 * statistics request open, and the first failure is logged once instead of on every request.
 */
@Component
public class StreamProcessorMetricsClient implements StreamProcessorMetrics {

    private static final Logger log = LoggerFactory.getLogger(StreamProcessorMetricsClient.class);

    private static final String COUNT = "COUNT";

    private final RestClient restClient;
    private final URI metricsUri;
    private final String metricName;
    private final Clock clock;

    /** Last successful reading of this instance, the reference of the next rate. */
    private final AtomicReference<Sample> lastSample = new AtomicReference<>();

    /** Whether the unavailability of the processor has already been reported. */
    private final AtomicBoolean unreachableReported = new AtomicBoolean();

    public StreamProcessorMetricsClient(
            StatisticsProperties properties,
            RestClient streamProcessorMetricsRestClient,
            Clock clock) {
        StatisticsProperties.StreamProcessor streamProcessor =
                Objects.requireNonNull(properties, "properties must not be null").streamProcessor();
        this.metricName = streamProcessor.processedEventsMetric();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.restClient = Objects.requireNonNull(
                streamProcessorMetricsRestClient,
                "streamProcessorMetricsRestClient must not be null");
        this.metricsUri = URI.create(withoutTrailingSlash(streamProcessor.metricsUrl().toString())
                + "/"
                + this.metricName);
    }

    private static String withoutTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public Optional<ProcessedEvents> read() {
        OptionalLong total = counter();
        if (total.isEmpty()) {
            return Optional.empty();
        }
        Instant at = clock.instant();
        Sample previous = lastSample.getAndSet(new Sample(at, total.getAsLong()));
        return Optional.of(new ProcessedEvents(total.getAsLong(), rate(previous, at, total.getAsLong())));
    }

    /**
     * The counter of the metric, summed over the measurements the processor publishes.
     *
     * <p>A Kafka Streams counter is published once per stream thread, so the processed events
     * of the topology are the sum of those measurements rather than the value of one of them.
     */
    private OptionalLong counter() {
        MetricDocument document;
        try {
            document = restClient.get()
                    .uri(metricsUri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(MetricDocument.class);
            unreachableReported.set(false);
        } catch (RestClientResponseException notPublished) {
            // The processor answers, but it does not publish this metric: a configuration
            // mismatch rather than an outage, so it is not reported as one.
            log.debug(
                    "the stream processor does not publish metric metric={} status={}",
                    metricName,
                    notPublished.getStatusCode());
            return OptionalLong.empty();
        } catch (RestClientException unavailable) {
            if (unreachableReported.compareAndSet(false, true)) {
                log.warn(
                        "statistics: the stream processor metrics are unavailable, reported as null reason={}",
                        unavailable.getMessage());
            } else {
                log.debug("statistics: the stream processor metrics are still unavailable");
            }
            return OptionalLong.empty();
        }
        if (document == null || document.measurements() == null) {
            return OptionalLong.empty();
        }
        double total = document.measurements().stream()
                .filter(measurement -> COUNT.equalsIgnoreCase(measurement.statistic()))
                .mapToDouble(Measurement::value)
                .sum();
        return OptionalLong.of(Math.round(total));
    }

    /**
     * Average rate between two readings of the same cumulative counter.
     *
     * <p>It returns {@code null} instead of a number when there is nothing honest to divide:
     * no previous reading, no time between the two, or a counter that went backwards because
     * the processor restarted.
     */
    private static Long rate(Sample previous, Instant at, long total) {
        if (previous == null || total < previous.total()) {
            return null;
        }
        long elapsedMillis = Duration.between(previous.at(), at).toMillis();
        if (elapsedMillis <= 0) {
            return null;
        }
        return Math.round((total - previous.total()) * 1000.0 / elapsedMillis);
    }

    /** A reading of the cumulative counter, used as the reference of the next rate. */
    private record Sample(Instant at, long total) {
    }

    /** The response of {@code /actuator/metrics/name}, of which only these fields are used. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MetricDocument(String name, List<Measurement> measurements) {
    }

    /** One measurement of a metric; a counter publishes a single COUNT. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Measurement(String statistic, double value) {
    }
}

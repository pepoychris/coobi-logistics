package com.coobi.logistics.logisticsapi.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of the two browser streams of MVP-6, bound from {@code coobi.stream.*}.
 *
 * <p>These values exist because a browser must never receive the throughput of the pipeline.
 * The interval of a stream bounds how often it reads its sources, the maximum of each event
 * family bounds how much one tick may carry, and the client settings bound how much of the
 * service one browser - or many - may hold.
 *
 * <p>As everywhere else in this service, the values are validated at startup: a stream that
 * silently did nothing because an interval was mistyped would be worse than a service that
 * refuses to start (Global Rule 11).
 *
 * @param events the {@code /api/v1/stream/events} endpoint
 * @param statistics the {@code /api/v1/stream/statistics} endpoint
 * @param client what a connected browser is allowed to cost
 */
@ConfigurationProperties(prefix = "coobi.stream")
@Validated
public record StreamProperties(
        @Valid @NotNull Events events,
        @Valid @NotNull Statistics statistics,
        @Valid @NotNull Client client) {

    /**
     * Sampling of the event stream.
     *
     * <p>The two maxima are per tick and per event family, so one tick of the stream carries
     * at most {@code maxAlertsPerPoll + maxVehiclesPerPoll} events no matter how many records
     * the pipeline processed in the meantime: the Kafka throughput is never forwarded to a
     * browser, it is sampled.
     *
     * @param pollInterval how often the read model is inspected for new events
     * @param maxAlertsPerPoll most alerts one tick may carry
     * @param maxVehiclesPerPoll most vehicle states one tick may carry
     */
    public record Events(
            @NotNull Duration pollInterval,
            @Min(1) int maxAlertsPerPoll,
            @Min(1) int maxVehiclesPerPoll) {
    }

    /**
     * Refresh rate of the statistics stream.
     *
     * @param interval how often the live statistics are read and sent; one second is the
     *        documented default, and a slower value is the way to make a stream of many
     *        browsers cheaper
     */
    public record Statistics(@NotNull Duration interval) {
    }

    /**
     * What one connected browser costs the service.
     *
     * @param maxSubscribers browsers one stream serves at a time; further connections are
     *        refused with {@code 503} instead of being accepted into an unbounded set
     * @param bufferSize frames held for one browser that has not read them yet; the oldest
     *        frame is dropped when the buffer is full, so a stalled reader cannot grow the
     *        memory of the service
     * @param heartbeatInterval how long a connection may stay silent before a comment frame
     *        is sent, which is what keeps intermediaries from closing it
     */
    public record Client(
            @Min(1) int maxSubscribers,
            @Min(1) int bufferSize,
            @NotNull Duration heartbeatInterval) {
    }
}

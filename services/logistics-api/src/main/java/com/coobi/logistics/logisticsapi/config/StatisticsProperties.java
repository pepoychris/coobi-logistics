package com.coobi.logistics.logisticsapi.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of the live statistics endpoint (MVP-5.4), bound from
 * {@code coobi.statistics.*}.
 *
 * <p>The values are validated at startup: a statistics endpoint that silently reports
 * {@code null} because its metric address was mistyped would be worse than a service that
 * refuses to start (Global Rule 11). An unreachable address is not a configuration error,
 * though, so reachability is handled at request time and reported as an absent value.
 *
 * @param streamProcessor where the processed-event counters are read from
 */
@ConfigurationProperties(prefix = "coobi.statistics")
@Validated
public record StatisticsProperties(@Valid @NotNull StreamProcessor streamProcessor) {

    /**
     * Kafka Streams metrics of the stream processor, as exposed over Actuator.
     *
     * @param metricsUrl base address of the Actuator metrics endpoint, without a metric name
     * @param processedEventsMetric metric that counts the records the topology has processed
     * @param timeout how long a metrics request may take before it counts as unavailable
     */
    public record StreamProcessor(
            @NotNull URI metricsUrl,
            @NotBlank String processedEventsMetric,
            @NotNull Duration timeout) {
    }
}

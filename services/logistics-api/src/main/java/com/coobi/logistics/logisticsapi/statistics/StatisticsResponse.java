package com.coobi.logistics.logisticsapi.statistics;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Live state of the stack, as {@code /api/v1/statistics} reports it (MVP-5.4).
 *
 * <p>Every value has a named source, and a source that is unavailable is reported as
 * {@code null} rather than replaced by a plausible number:
 *
 * <pre>
 * processedEvents   Kafka Streams counter of the stream processor, null if unreachable
 * eventsPerSecond   rate between the two latest readings of that counter, null until then
 * activeVehicles    vehicles whose latest state is MOVING, counted in the database
 * alertsGenerated   rows currently stored in `alerts`, counted in the database
 * uptimeSeconds     seconds this API instance has been serving
 * </pre>
 *
 * @param processedEvents telemetry records the processor has processed since it started
 * @param eventsPerSecond events per second observed between the two latest readings
 * @param activeVehicles vehicles currently reported as moving
 * @param alertsGenerated alerts currently stored
 * @param uptimeSeconds seconds this API instance has been serving
 */
@JsonPropertyOrder({
    "processedEvents",
    "eventsPerSecond",
    "activeVehicles",
    "alertsGenerated",
    "uptimeSeconds"
})
public record StatisticsResponse(
        Long processedEvents,
        Long eventsPerSecond,
        long activeVehicles,
        long alertsGenerated,
        long uptimeSeconds) {
}

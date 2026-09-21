package com.coobi.logistics.streamprocessor.event;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable envelope routed to the dead letter topic when a telemetry record cannot be
 * accepted (MVP-2.2).
 *
 * <p>Serialized form:
 *
 * <pre>
 * {
 *   "originalEvent": "{...}",               payload exactly as it was consumed
 *   "error": "data.latitude latitude must be less than or equal to 90",
 *   "failedAt": "2026-09-21T09:15:00.500Z", UTC instant the record was rejected
 *   "sourceTopic": "logistics.vehicle.location.v1"
 * }
 * </pre>
 *
 * <p>{@code originalEvent} is the payload text rather than a nested JSON document, so a
 * record that is not valid JSON at all still round-trips unchanged. It is the only
 * nullable component: a tombstone carries no payload to include.
 *
 * @param originalEvent payload exactly as consumed, or {@code null} when the record had none
 * @param error why the record was rejected
 * @param failedAt instant the record was rejected
 * @param sourceTopic topic the rejected record was consumed from
 */
@JsonPropertyOrder({"originalEvent", "error", "failedAt", "sourceTopic"})
public record DeadLetterEvent(String originalEvent, String error, Instant failedAt, String sourceTopic) {

    public DeadLetterEvent {
        Objects.requireNonNull(error, "error must not be null");
        Objects.requireNonNull(failedAt, "failedAt must not be null");
        Objects.requireNonNull(sourceTopic, "sourceTopic must not be null");
    }
}

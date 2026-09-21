package com.coobi.logistics.streamprocessor.event;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, versioned alert event (MVP-2.4, schema version 1).
 *
 * <p>Serialized form:
 *
 * <pre>
 * {
 *   "eventId": "0f3c1a5e-...",              unique per alert
 *   "eventType": "SPEEDING_DETECTED",
 *   "version": 1,
 *   "vehicleId": "TRUCK-00182",             also the Kafka message key
 *   "severity": "WARNING",
 *   "timestamp": "2026-09-21T09:15:00.123Z", UTC
 *   "data": { "speed": 137.2, "threshold": 120.0 }
 * }
 * </pre>
 *
 * <p>The canonical constructor rejects {@code null} components, an unsupported
 * {@code eventType} and any version other than the current schema, so an instance either
 * is a valid version-1 alert or does not exist. {@link #create(AlertType, String, Instant,
 * AlertData)} derives the event type and the severity from the {@link AlertType}, which
 * keeps the three fields consistent by construction.
 *
 * <p>Every component is immutable ({@link UUID}, {@link String}, primitives, enums,
 * {@link Instant} and another record), so the record is safe to share between threads.
 */
@JsonPropertyOrder({"eventId", "eventType", "version", "vehicleId", "severity", "timestamp", "data"})
public record AlertEvent(
        @NotNull(message = "eventId must not be null")
        UUID eventId,

        @NotBlank(message = "eventType must not be blank")
        String eventType,

        @Min(value = 1, message = "version must be greater than or equal to 1")
        int version,

        @NotNull(message = "vehicleId must not be null")
        @NotBlank(message = "vehicleId must not be blank")
        @Size(max = 64, message = "vehicleId must not be longer than 64 characters")
        String vehicleId,

        @NotNull(message = "severity must not be null")
        AlertSeverity severity,

        @NotNull(message = "timestamp must not be null")
        Instant timestamp,

        @NotNull(message = "data must not be null")
        @Valid
        AlertData data) {

    public static final int CURRENT_VERSION = 1;

    public AlertEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(vehicleId, "vehicleId must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(data, "data must not be null");
        if (!AlertType.isSupportedEventType(eventType)) {
            throw new IllegalArgumentException("eventType must be a supported alert type but was " + eventType);
        }
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("version must be " + CURRENT_VERSION + " but was " + version);
        }
    }

    /**
     * Creates a version-1 alert of the given type with a fresh unique identifier, the event
     * type and the severity of that type.
     */
    public static AlertEvent create(AlertType type, String vehicleId, Instant timestamp, AlertData data) {
        Objects.requireNonNull(type, "type must not be null");
        return new AlertEvent(
                UUID.randomUUID(), type.eventType(), CURRENT_VERSION, vehicleId, type.severity(), timestamp, data);
    }
}

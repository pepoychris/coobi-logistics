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
 * Immutable, versioned vehicle location telemetry event (schema version 1), as published
 * by the event generator (MVP-1.2).
 *
 * <p>Serialized form:
 *
 * <pre>
 * {
 *   "eventId": "6b9f6f5e-...",              unique per event
 *   "eventType": "VEHICLE_LOCATION_UPDATED",
 *   "version": 1,
 *   "vehicleId": "TRUCK-00001",             also the Kafka message key
 *   "timestamp": "2026-09-21T09:15:00.123Z", UTC
 *   "data": { "latitude": ..., "longitude": ..., "speed": ..., "heading": ... }
 * }
 * </pre>
 *
 * <p>The canonical constructor rejects {@code null} components and any combination that
 * is not the current schema, so an instance either is a valid version-1 event or does not
 * exist. The range constraints declared with {@code jakarta.validation} describe the same
 * contract for validation at the input boundary.
 *
 * <p>Every component is immutable ({@link UUID}, {@link String}, primitives,
 * {@link Instant} and another record), so the record is safe to share between threads.
 */
@JsonPropertyOrder({"eventId", "eventType", "version", "vehicleId", "timestamp", "data"})
public record VehicleLocationEvent(
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

        @NotNull(message = "timestamp must not be null")
        Instant timestamp,

        @NotNull(message = "data must not be null")
        @Valid
        VehicleLocationData data) {

    public static final String EVENT_TYPE_VEHICLE_LOCATION_UPDATED = "VEHICLE_LOCATION_UPDATED";
    public static final int CURRENT_VERSION = 1;

    public VehicleLocationEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(vehicleId, "vehicleId must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(data, "data must not be null");
        if (!EVENT_TYPE_VEHICLE_LOCATION_UPDATED.equals(eventType)) {
            throw new IllegalArgumentException(
                    "eventType must be " + EVENT_TYPE_VEHICLE_LOCATION_UPDATED + " but was " + eventType);
        }
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("version must be " + CURRENT_VERSION + " but was " + version);
        }
    }

    /** Creates the current event version with a fresh unique identifier. */
    public static VehicleLocationEvent create(String vehicleId, Instant timestamp, VehicleLocationData data) {
        return new VehicleLocationEvent(
                UUID.randomUUID(), EVENT_TYPE_VEHICLE_LOCATION_UPDATED, CURRENT_VERSION, vehicleId, timestamp, data);
    }
}

package com.coobi.logistics.streamprocessor.processing;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.Objects;

/**
 * Latest known state of one vehicle (MVP-3.1) together with the bookkeeping of the
 * stopped-vehicle window (MVP-3.2).
 *
 * <p>This document is the value of the {@code vehicle-state-store}, keyed by
 * {@code vehicleId}, so the latest telemetry of a vehicle can be reconstructed from the
 * state store - or from its changelog after a restart - without replaying the topic:
 *
 * <pre>
 * {
 *   "latitude": 39.4699,                      degrees, positive north
 *   "longitude": -0.3763,                     degrees, positive east
 *   "speed": 0.0,                             kilometres per hour
 *   "heading": 214.5,                         degrees clockwise from north
 *   "lastUpdate": "2026-09-21T09:15:00.123Z", UTC instant of the latest event
 *   "status": "MOVING",                       MOVING | STOPPED
 *   "windowStartedAt": "2026-09-21T09:15:00.123Z",
 *   "accumulatedMeters": 0.0
 * }
 * </pre>
 *
 * <p>The first six components are the state contract. {@code windowStartedAt} and
 * {@code accumulatedMeters} are the bookkeeping the stopped-vehicle detection needs to
 * decide the next transition: the instant the stationary window in progress started and
 * the distance the vehicle has covered since then. Keeping them here instead of keeping a
 * list of past positions means one record per vehicle is enough to decide both the status
 * and the alert.
 *
 * <p>Every component is immutable (primitives, {@link Instant} and an enum), so the record
 * is safe to share between threads.
 *
 * @param latitude degrees of the latest position, positive north
 * @param longitude degrees of the latest position, positive east
 * @param speed speed of the latest position in kilometres per hour
 * @param heading heading of the latest position in degrees clockwise from north
 * @param lastUpdate instant of the latest event of the vehicle
 * @param status movement status of the vehicle
 * @param windowStartedAt instant the stationary window in progress started
 * @param accumulatedMeters distance covered since {@code windowStartedAt}, in metres
 */
@JsonPropertyOrder({
    "latitude",
    "longitude",
    "speed",
    "heading",
    "lastUpdate",
    "status",
    "windowStartedAt",
    "accumulatedMeters"
})
public record VehicleState(
        double latitude,
        double longitude,
        double speed,
        double heading,
        Instant lastUpdate,
        VehicleStatus status,
        Instant windowStartedAt,
        double accumulatedMeters) {

    public VehicleState {
        requireFinite(latitude, "latitude");
        requireFinite(longitude, "longitude");
        requireFinite(speed, "speed");
        requireFinite(heading, "heading");
        Objects.requireNonNull(lastUpdate, "lastUpdate must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(windowStartedAt, "windowStartedAt must not be null");
        if (!Double.isFinite(accumulatedMeters)) {
            throw new IllegalArgumentException(
                    "accumulatedMeters must be a finite number but was " + accumulatedMeters);
        }
        if (accumulatedMeters < 0) {
            throw new IllegalArgumentException(
                    "accumulatedMeters must be greater than or equal to 0 but was " + accumulatedMeters);
        }
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be a finite number but was " + value);
        }
    }
}

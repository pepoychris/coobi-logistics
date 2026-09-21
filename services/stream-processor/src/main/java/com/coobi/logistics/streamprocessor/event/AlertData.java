package com.coobi.logistics.streamprocessor.event;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Measured value that triggered an alert, next to the configured threshold.
 *
 * <p>The record is immutable and every component is a primitive, so instances are
 * inherently thread-safe. The compact constructor rejects non-finite numbers, which the
 * JSON format cannot represent.
 *
 * <p>The unit of the threshold follows the alert type, because each detection measures what
 * it can: {@code SPEEDING} compares a speed with the speed limit in km/h and
 * {@code VEHICLE_STOPPED} compares the distance covered with the movement threshold in
 * metres. {@code docs/stream-processor.md} documents the pair per alert type.
 *
 * @param speed speed observed in the telemetry event, in kilometres per hour
 * @param threshold configured threshold of the detection that produced the alert
 */
@JsonPropertyOrder({"speed", "threshold"})
public record AlertData(
        double speed,

        double threshold) {

    public AlertData {
        requireFinite(speed, "speed");
        requireFinite(threshold, "threshold");
        if (speed < 0) {
            throw new IllegalArgumentException("speed must be greater than or equal to 0 but was " + speed);
        }
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be greater than 0 but was " + threshold);
        }
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be a finite number but was " + value);
        }
    }
}

package com.coobi.logistics.streamprocessor.event;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Measured value that triggered an alert, next to the configured threshold.
 *
 * <p>The record is immutable and every component is a primitive, so instances are
 * inherently thread-safe. The compact constructor rejects non-finite numbers, which the
 * JSON format cannot represent.
 *
 * @param speed observed speed in kilometres per hour
 * @param threshold configured limit the observation crossed, in kilometres per hour
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

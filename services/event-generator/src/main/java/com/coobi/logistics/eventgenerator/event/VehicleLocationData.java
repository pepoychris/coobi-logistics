package com.coobi.logistics.eventgenerator.event;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/**
 * Movement payload of a location update.
 *
 * <p>The record is immutable and every component is a primitive, so instances are
 * inherently thread-safe. The compact constructor rejects non-finite numbers, which
 * {@code @DecimalMin}/{@code @DecimalMax} cannot judge, and Bean Validation owns the
 * ranges of the contract:
 *
 * <pre>
 * latitude   -90.0 .. 90.0
 * longitude  -180.0 .. 180.0
 * speed      &gt;= 0.0
 * heading    0.0 .. &lt; 360.0
 * </pre>
 *
 * @param latitude degrees, positive north
 * @param longitude degrees, positive east
 * @param speed kilometres per hour
 * @param heading degrees clockwise from north
 */
@JsonPropertyOrder({"latitude", "longitude", "speed", "heading"})
public record VehicleLocationData(
        @DecimalMin(value = "-90.0", message = "latitude must be greater than or equal to -90")
        @DecimalMax(value = "90.0", message = "latitude must be less than or equal to 90")
        double latitude,

        @DecimalMin(value = "-180.0", message = "longitude must be greater than or equal to -180")
        @DecimalMax(value = "180.0", message = "longitude must be less than or equal to 180")
        double longitude,

        @DecimalMin(value = "0.0", message = "speed must be greater than or equal to 0")
        double speed,

        @DecimalMin(value = "0.0", message = "heading must be greater than or equal to 0")
        @DecimalMax(value = "360.0", inclusive = false, message = "heading must be less than 360")
        double heading) {

    public VehicleLocationData {
        requireFinite(latitude, "latitude");
        requireFinite(longitude, "longitude");
        requireFinite(speed, "speed");
        requireFinite(heading, "heading");
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be a finite number but was " + value);
        }
    }
}

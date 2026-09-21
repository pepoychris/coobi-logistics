package com.coobi.logistics.eventgenerator.simulation;

import java.util.Objects;

/**
 * Validated input of the simulator, independent of Spring configuration.
 *
 * <p>Every invariant is checked in the compact constructor so a malformed
 * configuration fails at bean creation instead of producing invalid telemetry.
 *
 * @param vehicleIdPrefix prefix of the deterministic vehicle identifiers
 * @param vehicleCount number of simulated vehicles
 * @param randomSeed seed of the deterministic trajectory generator
 * @param centerLatitude latitude of the demonstration area centre, degrees
 * @param centerLongitude longitude of the demonstration area centre, degrees
 * @param areaRadiusKilometres radius of the demonstration area
 * @param minSpeedKph lowest speed a vehicle is clamped to
 * @param maxSpeedKph highest speed a vehicle is clamped to
 * @param maxSpeedDeltaKph largest speed change applied by a single update
 * @param maxHeadingDeltaDegrees largest heading change applied by a single update
 * @param maxElapsedSeconds longest simulated step applied to a single update
 */
public record VehicleSimulationSettings(
        String vehicleIdPrefix,
        int vehicleCount,
        long randomSeed,
        double centerLatitude,
        double centerLongitude,
        double areaRadiusKilometres,
        double minSpeedKph,
        double maxSpeedKph,
        double maxSpeedDeltaKph,
        double maxHeadingDeltaDegrees,
        double maxElapsedSeconds) {

    /** Keeps the longitude scale usable, since it is derived from {@code cos(latitude)}. */
    private static final double MAXIMUM_ABSOLUTE_CENTER_LATITUDE = 85.0;

    public VehicleSimulationSettings {
        Objects.requireNonNull(vehicleIdPrefix, "vehicleIdPrefix must not be null");
        if (vehicleIdPrefix.isBlank() || vehicleIdPrefix.length() > 32) {
            throw new IllegalArgumentException(
                    "vehicleIdPrefix must be non-blank and at most 32 characters but was '" + vehicleIdPrefix + "'");
        }
        if (vehicleCount < 1) {
            throw new IllegalArgumentException("vehicleCount must be at least 1 but was " + vehicleCount);
        }
        if (!Double.isFinite(centerLatitude) || Math.abs(centerLatitude) > MAXIMUM_ABSOLUTE_CENTER_LATITUDE) {
            throw new IllegalArgumentException(
                    "centerLatitude must be within +/-" + MAXIMUM_ABSOLUTE_CENTER_LATITUDE + " but was " + centerLatitude);
        }
        if (!Double.isFinite(centerLongitude) || centerLongitude < -180.0 || centerLongitude > 180.0) {
            throw new IllegalArgumentException("centerLongitude must be within -180..180 but was " + centerLongitude);
        }
        if (!Double.isFinite(areaRadiusKilometres) || areaRadiusKilometres <= 0.0) {
            throw new IllegalArgumentException(
                    "areaRadiusKilometres must be greater than 0 but was " + areaRadiusKilometres);
        }
        if (!Double.isFinite(minSpeedKph) || minSpeedKph < 0.0) {
            throw new IllegalArgumentException("minSpeedKph must not be negative but was " + minSpeedKph);
        }
        if (!Double.isFinite(maxSpeedKph) || maxSpeedKph < minSpeedKph) {
            throw new IllegalArgumentException(
                    "maxSpeedKph must be greater than or equal to minSpeedKph but was " + maxSpeedKph);
        }
        if (!Double.isFinite(maxSpeedDeltaKph) || maxSpeedDeltaKph < 0.0) {
            throw new IllegalArgumentException("maxSpeedDeltaKph must not be negative but was " + maxSpeedDeltaKph);
        }
        if (!Double.isFinite(maxHeadingDeltaDegrees) || maxHeadingDeltaDegrees < 0.0
                || maxHeadingDeltaDegrees > 180.0) {
            throw new IllegalArgumentException(
                    "maxHeadingDeltaDegrees must be within 0..180 but was " + maxHeadingDeltaDegrees);
        }
        if (!Double.isFinite(maxElapsedSeconds) || maxElapsedSeconds <= 0.0) {
            throw new IllegalArgumentException("maxElapsedSeconds must be greater than 0 but was " + maxElapsedSeconds);
        }
    }
}

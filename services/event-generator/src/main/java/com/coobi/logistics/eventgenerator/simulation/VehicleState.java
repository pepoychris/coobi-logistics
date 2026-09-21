package com.coobi.logistics.eventgenerator.simulation;

import java.time.Instant;

/**
 * Mutable state of one simulated vehicle.
 *
 * <p>Owned by {@link VehicleTelemetrySimulator}, which is the only writer, so no
 * synchronisation is needed: the generator publishes from a single scheduled thread.
 */
final class VehicleState {

    private final String vehicleId;
    private double latitude;
    private double longitude;
    private double speedKph;
    private double headingDegrees;
    private Instant lastUpdateAt;

    VehicleState(
            String vehicleId,
            double latitude,
            double longitude,
            double speedKph,
            double headingDegrees,
            Instant lastUpdateAt) {
        this.vehicleId = vehicleId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.speedKph = speedKph;
        this.headingDegrees = headingDegrees;
        this.lastUpdateAt = lastUpdateAt;
    }

    String vehicleId() {
        return vehicleId;
    }

    double latitude() {
        return latitude;
    }

    double longitude() {
        return longitude;
    }

    double speedKph() {
        return speedKph;
    }

    double headingDegrees() {
        return headingDegrees;
    }

    Instant lastUpdateAt() {
        return lastUpdateAt;
    }

    void apply(
            double latitude,
            double longitude,
            double speedKph,
            double headingDegrees,
            Instant updatedAt) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.speedKph = speedKph;
        this.headingDegrees = headingDegrees;
        this.lastUpdateAt = updatedAt;
    }
}

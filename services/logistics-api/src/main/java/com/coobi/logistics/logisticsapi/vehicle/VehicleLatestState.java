package com.coobi.logistics.logisticsapi.vehicle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Latest known state of one vehicle - the {@code vehicle_latest_state} table of MVP-4.2.
 *
 * <p>This is a read model: the stream processor keeps exactly one row per vehicle and
 * overwrites it in place, and this service only reads it. The class is therefore immutable
 * - every field is set by Hibernate and exposed through a getter, with no setter and no
 * constructor of its own - and it never leaves the API: the endpoints answer with DTOs, so
 * the storage mapping can change without changing the response contract.
 *
 * <p>The row is written in the same transaction as the {@code vehicles} registry row it
 * belongs to (MVP-4.2), so a vehicle that has ever sent telemetry is present here and a
 * vehicle that has not is unknown to the fleet view.
 */
@Entity
@Table(name = "vehicle_latest_state")
public class VehicleLatestState {

    @Id
    @Column(name = "vehicle_id", nullable = false, length = 64, updatable = false)
    private String vehicleId;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    /** Speed of the latest event, in kilometres per hour. */
    @Column(name = "speed", nullable = false)
    private double speed;

    /** Heading of the latest event, in degrees clockwise from north. */
    @Column(name = "heading", nullable = false)
    private double heading;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private VehicleStatus status;

    /** Instant of the latest telemetry event, not of the write. */
    @Column(name = "last_update", nullable = false)
    private Instant lastUpdate;

    /** Instant the row was written, so a stalled vehicle is distinguishable from a stale row. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by Hibernate; the API never builds one of these itself. */
    protected VehicleLatestState() {
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public double getSpeed() {
        return speed;
    }

    public double getHeading() {
        return heading;
    }

    public VehicleStatus getStatus() {
        return status;
    }

    public Instant getLastUpdate() {
        return lastUpdate;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

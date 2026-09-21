package com.coobi.logistics.streamprocessor.processing;

/**
 * Movement status of one vehicle (MVP-3.2).
 *
 * <p>It is the {@code status} of the state document kept per {@code vehicleId}: a vehicle
 * is {@link #MOVING} while it keeps covering ground and becomes {@link #STOPPED} once it
 * has stayed within the movement threshold for the whole stopped window. Only the
 * transition into {@link #STOPPED} produces an alert, so a vehicle that remains parked
 * produces exactly one alert.
 */
public enum VehicleStatus {

    MOVING,
    STOPPED
}

package com.coobi.logistics.logisticsapi.vehicle;

/**
 * Movement status of a vehicle, as reported by the latest telemetry event.
 *
 * <p>The two constants are the ones the {@code vehicle_latest_state_status_check} constraint
 * of the schema accepts (MVP-4.2), and the ones the alert contract uses, so a status the
 * database cannot hold cannot be requested through the API either.
 */
public enum VehicleStatus {

    /** The vehicle reported movement inside the stopped-vehicle window. */
    MOVING,

    /** The vehicle has stayed within the movement threshold for the whole window. */
    STOPPED
}

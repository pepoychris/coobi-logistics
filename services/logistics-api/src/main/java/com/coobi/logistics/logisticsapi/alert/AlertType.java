package com.coobi.logistics.logisticsapi.alert;

/**
 * Kind of alert the processor produces, and the {@code eventType} of the alert contract.
 *
 * <p>The two constants are the ones the {@code alerts_type_check} constraint of the schema
 * accepts (MVP-4.3), so the filter of the alert list cannot ask for an alert the database
 * cannot hold.
 */
public enum AlertType {

    /** The vehicle crossed the configured speed limit. */
    SPEEDING_DETECTED,

    /** The vehicle stayed inside the movement threshold for the whole window. */
    VEHICLE_STOPPED_DETECTED
}

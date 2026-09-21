package com.coobi.logistics.streamprocessor.processing;

/**
 * Speed state of one vehicle (MVP-2.3).
 *
 * <p>The detection is a two-state machine. A vehicle starts in {@link #NORMAL}, moves to
 * {@link #SPEEDING} on the first telemetry above the limit and returns to {@link #NORMAL}
 * on the first telemetry at or below it. Only the transition into {@link #SPEEDING}
 * produces an alert, so a vehicle that stays above the limit produces exactly one alert.
 */
public enum VehicleSpeedState {

    NORMAL,
    SPEEDING;

    /** State stored for a vehicle, defaulting to {@link #NORMAL} before its first event. */
    public static VehicleSpeedState from(String stored) {
        return stored == null ? NORMAL : valueOf(stored);
    }
}

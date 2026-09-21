package com.coobi.logistics.logisticsapi.integration.support;

import com.coobi.logistics.eventgenerator.event.VehicleLocationEvent;
import com.coobi.logistics.eventgenerator.simulation.VehicleSimulationSettings;
import com.coobi.logistics.eventgenerator.simulation.VehicleTelemetrySimulator;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The telemetry scenario of the smoke test, produced by the simulator of the event generator
 * (MVP-1) and expressed in the version-1 location contract it publishes.
 *
 * <p>The scenario is the smallest one that crosses both detections of the processor:
 *
 * <pre>
 * t0         the vehicle drives at 137 km/h  -> above the 120 km/h limit, so it is reported
 * t0 + 400s  the vehicle is parked           -> a stationary window opens at this position
 * t0 + 750s  the vehicle is still parked     -> 350 s without movement: it is reported stopped
 * </pre>
 *
 * <p>The timeline is the telemetry timeline, not the wall clock: the simulator reads
 * {@link MutableClock}, and the processor decides whether the stationary window elapsed by
 * comparing the timestamps of the records it consumes. A five-minute window is therefore
 * verified without waiting five minutes, and without a single fixed sleep.
 *
 * <p>Two simulators share the vehicle identifier: one that only ever reports the speed above
 * the limit, and one that only ever reports a parked vehicle. Both are deterministic - the same
 * seeds replay the same positions - so the scenario is reproducible, and the assertions of the
 * smoke test compare the API against these events rather than against hardcoded coordinates.
 */
public final class GeneratedTelemetry {

    /** Speed limit and stopped window the smoke test configures the processor with. */
    public static final double SPEED_LIMIT_KPH = 120.0;

    public static final long STOPPED_WINDOW_SECONDS = 300L;

    public static final double MOVEMENT_THRESHOLD_METERS = 50.0;

    /** Speed of the driving phase: above the limit, so the crossing is unambiguous. */
    private static final double SPEEDING_KPH = SPEED_LIMIT_KPH + 17.2;

    /** Instants of the parked phase, both beyond the stationary window of the processor. */
    private static final long FIRST_PARKED_SECONDS = 400L;

    private static final long LAST_PARKED_SECONDS = 750L;

    private static final String VEHICLE_ID_PREFIX = "TRUCK-";

    /** The demonstration area of the generator (Valencia, Spain). */
    private static final double CENTER_LATITUDE = 39.4699;

    private static final double CENTER_LONGITUDE = -0.3763;

    private static final double AREA_RADIUS_KILOMETRES = 5.0;

    private static final double MAXIMUM_ELAPSED_SECONDS = 60.0;

    private static final long DRIVING_SEED = 2_026_092_101L;

    private static final long PARKED_SEED = 2_026_092_102L;

    private static final int ONE_VEHICLE = 1;

    private final VehicleLocationEvent speeding;
    private final VehicleLocationEvent firstParked;
    private final VehicleLocationEvent lastParked;

    private GeneratedTelemetry(
            VehicleLocationEvent speeding, VehicleLocationEvent firstParked, VehicleLocationEvent lastParked) {
        this.speeding = speeding;
        this.firstParked = firstParked;
        this.lastParked = lastParked;
    }

    /**
     * Generates the scenario at {@code startedAt}: a vehicle above the speed limit, then parked
     * long enough for the stopped window of the processor to elapse.
     */
    public static GeneratedTelemetry drivingAboveTheLimitThenParking(Instant startedAt) {
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        MutableClock clock = new MutableClock(startedAt);

        VehicleTelemetrySimulator driving =
                new VehicleTelemetrySimulator(settings(SPEEDING_KPH, SPEEDING_KPH, DRIVING_SEED), clock);
        VehicleLocationEvent speeding = driving.nextEvent();

        VehicleTelemetrySimulator parked =
                new VehicleTelemetrySimulator(settings(0.0, 0.0, PARKED_SEED), clock);
        clock.set(startedAt.plusSeconds(FIRST_PARKED_SECONDS));
        VehicleLocationEvent firstParked = parked.nextEvent();
        clock.set(startedAt.plusSeconds(LAST_PARKED_SECONDS));
        VehicleLocationEvent lastParked = parked.nextEvent();

        return new GeneratedTelemetry(speeding, firstParked, lastParked);
    }

    /** Identifier of the simulated vehicle, which is also the key of every published record. */
    public String vehicleId() {
        return speeding.vehicleId();
    }

    /** Every event of the scenario, in the order it has to be published in. */
    public List<VehicleLocationEvent> events() {
        return List.of(speeding, firstParked, lastParked);
    }

    /** The event that crosses the speed limit. */
    public VehicleLocationEvent speedingEvent() {
        return speeding;
    }

    /** The latest event of the scenario, which is the state the API has to report. */
    public VehicleLocationEvent parkedEvent() {
        return lastParked;
    }

    /** The instant the vehicle parked, which the stopped detection reports as the alert time. */
    public Instant parkedAt() {
        return lastParked.timestamp();
    }

    private static VehicleSimulationSettings settings(double minimumSpeedKph, double maximumSpeedKph, long seed) {
        return new VehicleSimulationSettings(
                VEHICLE_ID_PREFIX,
                ONE_VEHICLE,
                seed,
                CENTER_LATITUDE,
                CENTER_LONGITUDE,
                AREA_RADIUS_KILOMETRES,
                minimumSpeedKph,
                maximumSpeedKph,
                // A fixed speed and a fixed heading: the scenario keeps the trajectory of every
                // phase, and the only variable left is the timeline the test moves.
                0.0,
                0.0,
                MAXIMUM_ELAPSED_SECONDS);
    }
}

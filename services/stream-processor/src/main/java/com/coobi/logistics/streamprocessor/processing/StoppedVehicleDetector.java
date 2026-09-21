package com.coobi.logistics.streamprocessor.processing;

import com.coobi.logistics.streamprocessor.event.VehicleLocationData;
import com.coobi.logistics.streamprocessor.event.VehicleLocationEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * {@code MOVING} to {@code STOPPED} state machine of one vehicle (MVP-3.2).
 *
 * <p>A vehicle is reported as stopped once it has stayed effectively stationary for the
 * configured window. "Effectively stationary" is judged on the distance actually covered,
 * not on the reported speed: every step between two consecutive positions is measured with
 * {@link Haversine} and added up, and the window restarts whenever the accumulated
 * distance reaches the configured threshold. Telemetry that keeps the vehicle inside the
 * threshold for the whole window therefore produces exactly one alert, while a vehicle
 * that drives away resets the status and can alert again after a new stationary window.
 *
 * <pre>
 * MOVING  -- accumulated &gt;= threshold ----------------------&gt; MOVING, window restarts
 * MOVING  -- accumulated &lt; threshold and elapsed &gt;= window -&gt; STOPPED (one alert)
 * MOVING  -- accumulated &lt; threshold and elapsed &lt; window ---&gt; MOVING, window continues
 * STOPPED -- accumulated &gt;= threshold ----------------------&gt; MOVING, window restarts
 * STOPPED -- accumulated &lt; threshold -----------------------&gt; STOPPED (no alert)
 * </pre>
 *
 * <p>The elapsed time is read from the telemetry timestamps rather than from the clock, so
 * a test can drive a five-minute window without waiting and a restart of the service
 * cannot shift a window that is already in progress.
 *
 * <p>The detector holds no state of its own: it receives the previous state of the vehicle
 * and returns the next one, which keeps it free of Kafka and Spring types and directly
 * testable.
 */
public final class StoppedVehicleDetector {

    private final Duration window;
    private final double movementThresholdMeters;

    /**
     * @param window time a vehicle must stay within the threshold to be reported as stopped
     * @param movementThresholdMeters total distance below which movement counts as no
     *     movement over the window
     */
    public StoppedVehicleDetector(Duration window, double movementThresholdMeters) {
        Objects.requireNonNull(window, "window must not be null");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be greater than zero but was " + window);
        }
        if (!Double.isFinite(movementThresholdMeters) || movementThresholdMeters <= 0) {
            throw new IllegalArgumentException("movementThresholdMeters must be a finite number greater "
                    + "than 0 but was " + movementThresholdMeters);
        }
        this.window = window;
        this.movementThresholdMeters = movementThresholdMeters;
    }

    /** Configured length of the stationary window. */
    public Duration window() {
        return window;
    }

    /** Configured total distance below which movement counts as no movement, in metres. */
    public double movementThresholdMeters() {
        return movementThresholdMeters;
    }

    /**
     * Advances the state of one vehicle with the event that has just been consumed.
     *
     * @param previous state of the vehicle after its previous event, or {@code null} when
     *     this is the first event seen for the vehicle
     * @param event accepted telemetry of the vehicle, keyed by {@code vehicleId}
     * @return the state of the vehicle after this event
     */
    public VehicleState advance(VehicleState previous, VehicleLocationEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        VehicleLocationData data = event.data();

        if (previous == null) {
            // The first event of a vehicle opens its window at its own position, so the
            // vehicle cannot be reported as stopped before the window has really passed.
            return state(data, event.timestamp(), VehicleStatus.MOVING, event.timestamp(), 0.0);
        }

        double step = Haversine.distanceMeters(
                previous.latitude(), previous.longitude(), data.latitude(), data.longitude());
        double accumulated = previous.accumulatedMeters() + step;

        if (accumulated >= movementThresholdMeters) {
            // The vehicle covered ground, so the window restarts at this position.
            return state(data, event.timestamp(), VehicleStatus.MOVING, event.timestamp(), 0.0);
        }
        if (previous.status() == VehicleStatus.MOVING && hasWindowElapsed(previous.windowStartedAt(), event.timestamp())) {
            return state(data, event.timestamp(), VehicleStatus.STOPPED, previous.windowStartedAt(), accumulated);
        }
        return state(data, event.timestamp(), previous.status(), previous.windowStartedAt(), accumulated);
    }

    private boolean hasWindowElapsed(Instant windowStartedAt, Instant timestamp) {
        Duration elapsed = Duration.between(windowStartedAt, timestamp);
        return !elapsed.isNegative() && elapsed.compareTo(window) >= 0;
    }

    private static VehicleState state(
            VehicleLocationData data,
            Instant lastUpdate,
            VehicleStatus status,
            Instant windowStartedAt,
            double accumulatedMeters) {

        return new VehicleState(
                data.latitude(),
                data.longitude(),
                data.speed(),
                data.heading(),
                lastUpdate,
                status,
                windowStartedAt,
                accumulatedMeters);
    }
}

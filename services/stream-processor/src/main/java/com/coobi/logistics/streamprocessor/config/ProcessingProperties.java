package com.coobi.logistics.streamprocessor.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Detection thresholds of the stream processor (MVP-2.3 and MVP-3.2).
 *
 * <p>Every threshold is bounded: a value that cannot be used as a threshold aborts
 * startup instead of degrading silently (Global Rule 11).
 */
@ConfigurationProperties(prefix = "coobi.processing")
@Validated
public class ProcessingProperties {

    /** Speed strictly above which a vehicle is reported as speeding, in km/h. */
    @DecimalMin(value = "0.0", inclusive = false, message = "the speed limit must be greater than 0")
    private double speedLimitKph = 120.0;

    /**
     * Time a vehicle must remain effectively stationary before it is reported as stopped,
     * in seconds.
     */
    @Min(value = 1, message = "the stopped window must be at least 1 second")
    private long stoppedWindowSeconds = 300;

    /**
     * Total distance below which movement counts as no movement over the stopped window,
     * in metres.
     */
    @DecimalMin(value = "0.0", inclusive = false, message = "the movement threshold must be greater than 0")
    private double movementThresholdMeters = 50.0;

    public double getSpeedLimitKph() {
        return speedLimitKph;
    }

    public void setSpeedLimitKph(double speedLimitKph) {
        if (!Double.isFinite(speedLimitKph)) {
            throw new IllegalArgumentException("the speed limit must be a finite number but was " + speedLimitKph);
        }
        this.speedLimitKph = speedLimitKph;
    }

    public long getStoppedWindowSeconds() {
        return stoppedWindowSeconds;
    }

    public void setStoppedWindowSeconds(long stoppedWindowSeconds) {
        this.stoppedWindowSeconds = stoppedWindowSeconds;
    }

    public double getMovementThresholdMeters() {
        return movementThresholdMeters;
    }

    public void setMovementThresholdMeters(double movementThresholdMeters) {
        if (!Double.isFinite(movementThresholdMeters)) {
            throw new IllegalArgumentException(
                    "the movement threshold must be a finite number but was " + movementThresholdMeters);
        }
        this.movementThresholdMeters = movementThresholdMeters;
    }
}

package com.coobi.logistics.streamprocessor.config;

import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Detection thresholds of the stream processor (MVP-2.3).
 *
 * <p>Only the speeding threshold is implemented in this milestone; the stopped-vehicle
 * thresholds documented in `.env.example` belong to MVP-3.
 */
@ConfigurationProperties(prefix = "coobi.processing")
@Validated
public class ProcessingProperties {

    /** Speed strictly above which a vehicle is reported as speeding, in km/h. */
    @DecimalMin(value = "0.0", inclusive = false, message = "the speed limit must be greater than 0")
    private double speedLimitKph = 120.0;

    public double getSpeedLimitKph() {
        return speedLimitKph;
    }

    public void setSpeedLimitKph(double speedLimitKph) {
        if (!Double.isFinite(speedLimitKph)) {
            throw new IllegalArgumentException("the speed limit must be a finite number but was " + speedLimitKph);
        }
        this.speedLimitKph = speedLimitKph;
    }
}

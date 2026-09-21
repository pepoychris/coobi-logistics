package com.coobi.logistics.logisticsapi.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A clock a test can move forward, for the values the service derives from elapsed time: the
 * uptime and the events-per-second rate between two readings of the processor counter.
 */
public final class AdvanceableClock extends Clock {

    private Instant instant;

    public AdvanceableClock(Instant start) {
        this.instant = Objects.requireNonNull(start, "start must not be null");
    }

    /**
     * Moves the clock forward.
     *
     * @param amount how much time the test pretends has passed
     */
    public void advance(Duration amount) {
        instant = instant.plus(amount);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}

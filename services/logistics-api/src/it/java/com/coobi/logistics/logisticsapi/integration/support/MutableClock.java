package com.coobi.logistics.logisticsapi.integration.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A clock the smoke test moves by hand.
 *
 * <p>The simulator of the event generator reads the clock once per event, and the processor
 * decides whether a stationary window has elapsed by comparing the timestamps of the telemetry
 * it consumes - never the wall clock. Handing the simulator a clock the test moves therefore
 * drives a five-minute stopped window in milliseconds, which is what keeps the smoke test free
 * of a wait proportional to the window it verifies.
 */
public final class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    private MutableClock(Instant start, ZoneId zone) {
        this.instant = Objects.requireNonNull(start, "start must not be null");
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
    }

    /** Moves the clock to {@code instant}, which is what the next event is stamped with. */
    public void set(Instant instant) {
        this.instant = Objects.requireNonNull(instant, "instant must not be null");
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }
}

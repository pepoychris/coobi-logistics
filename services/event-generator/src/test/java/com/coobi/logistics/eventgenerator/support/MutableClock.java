package com.coobi.logistics.eventgenerator.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Test clock with explicit time control, so tests can drive the simulator with exact
 * elapsed times instead of sleeping.
 */
public final class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    public MutableClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    public MutableClock(Instant start, ZoneId zone) {
        this.instant = start;
        this.zone = zone;
    }

    public void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId otherZone) {
        return new MutableClock(instant, otherZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}

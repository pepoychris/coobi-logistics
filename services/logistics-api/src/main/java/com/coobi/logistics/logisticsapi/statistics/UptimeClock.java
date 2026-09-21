package com.coobi.logistics.logisticsapi.statistics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * How long this API instance has been serving.
 *
 * <p>Uptime is the one statistic the service knows about itself, so it is measured here
 * rather than guessed: the instant the bean was created is the reference, and every read
 * subtracts it from the current instant of the injected {@link Clock}. A clock that moved
 * backwards reports zero seconds instead of a negative uptime.
 */
public class UptimeClock {

    private final Clock clock;
    private final Instant startedAt;

    /**
     * Uptime of this instance, starting when the instance is created.
     *
     * @param clock clock of the service
     */
    public UptimeClock(Clock clock) {
        this(clock, clock.instant());
    }

    /**
     * Uptime since a known instant, which lets a test place the start of the service.
     *
     * @param clock clock of the service
     * @param startedAt instant the service is considered started
     */
    UptimeClock(Clock clock, Instant startedAt) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
    }

    /**
     * @return whole seconds this instance has been up, never negative
     */
    public long uptimeSeconds() {
        return Math.max(Duration.between(startedAt, clock.instant()).getSeconds(), 0L);
    }
}

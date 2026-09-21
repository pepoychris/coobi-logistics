package com.coobi.logistics.logisticsapi.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.logisticsapi.support.AdvanceableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Uptime is measured, not invented: it is the difference between the instant the service
 * started and the instant of the reading.
 */
class UptimeClockTest {

    private static final Instant STARTED_AT = Instant.parse("2026-09-21T09:00:00Z");

    @Test
    void reportsTheSecondsSinceTheServiceStarted() {
        AdvanceableClock clock = new AdvanceableClock(STARTED_AT);
        UptimeClock uptime = new UptimeClock(clock, STARTED_AT);

        assertThat(uptime.uptimeSeconds()).isZero();

        clock.advance(Duration.ofSeconds(8_271));

        assertThat(uptime.uptimeSeconds()).isEqualTo(8_271L);
    }

    @Test
    void neverReportsANegativeUptime() {
        AdvanceableClock clock = new AdvanceableClock(STARTED_AT.minusSeconds(30));
        UptimeClock uptime = new UptimeClock(clock, STARTED_AT);

        assertThat(uptime.uptimeSeconds()).isZero();
    }

    @Test
    void startsCountingWhenTheBeanIsCreated() {
        Clock clock = Clock.fixed(STARTED_AT, java.time.ZoneOffset.UTC);

        assertThat(new UptimeClock(clock).uptimeSeconds()).isZero();
    }
}

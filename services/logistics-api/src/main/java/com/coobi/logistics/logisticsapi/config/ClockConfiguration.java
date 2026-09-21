package com.coobi.logistics.logisticsapi.config;

import com.coobi.logistics.logisticsapi.statistics.UptimeClock;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock of the service.
 *
 * <p>Every instant this service reports is derived from an injected {@link Clock}, so the
 * uptime and the sampling of the stream-processor metrics are testable without waiting for
 * wall-clock time to pass. The clock is UTC, like every timestamp of the stack.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    UptimeClock uptimeClock(Clock clock) {
        return new UptimeClock(clock);
    }
}

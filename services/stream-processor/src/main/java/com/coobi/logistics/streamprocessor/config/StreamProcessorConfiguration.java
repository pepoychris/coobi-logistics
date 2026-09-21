package com.coobi.logistics.streamprocessor.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the processing domain, which is deliberately free of Spring and Kafka types.
 */
@Configuration(proxyBeanMethods = false)
public class StreamProcessorConfiguration {

    /** All timestamps of the platform are UTC (Global Rule 12). */
    @Bean
    public Clock processingClock() {
        return Clock.systemUTC();
    }
}

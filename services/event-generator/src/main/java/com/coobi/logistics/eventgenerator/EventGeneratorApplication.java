package com.coobi.logistics.eventgenerator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Vehicle telemetry generator: simulates a fleet and publishes versioned location
 * events to Kafka (MVP-1).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class EventGeneratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventGeneratorApplication.class, args);
    }
}

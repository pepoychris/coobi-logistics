package com.coobi.logistics.logisticsapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Read-only REST API over the derived fleet state of Coobi Logistics (MVP-5).
 *
 * <p>The service owns no migration and writes nothing: it maps the tables the
 * stream-processor migrations create and answers the operator views of the fleet
 * - vehicles, alerts and live statistics.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LogisticsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogisticsApiApplication.class, args);
    }
}

package com.coobi.logistics.streamprocessor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Stream processor: consumes versioned vehicle telemetry from Kafka, validates it, routes
 * invalid events to the dead letter topic and turns speeding transitions into alerts
 * (MVP-2).
 *
 * <p>Kafka Streams is enabled by
 * {@link com.coobi.logistics.streamprocessor.config.KafkaStreamsConfiguration} rather than
 * here, so the application class carries no infrastructure annotation and the partial test
 * slices (for example {@code @JsonTest}) can start without the streams infrastructure.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class StreamProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreamProcessorApplication.class, args);
    }
}

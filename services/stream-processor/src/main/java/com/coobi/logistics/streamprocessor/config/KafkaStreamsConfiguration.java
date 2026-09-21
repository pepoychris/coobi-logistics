package com.coobi.logistics.streamprocessor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;

/**
 * Enables the Kafka Streams infrastructure of the service.
 *
 * <p>Declared next to the other infrastructure configuration instead of on the
 * application class, so the application class stays free of infrastructure annotations.
 */
@Configuration(proxyBeanMethods = false)
@EnableKafkaStreams
public class KafkaStreamsConfiguration {
}

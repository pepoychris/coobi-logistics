package com.coobi.logistics.streamprocessor.config;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Kafka clients used by the processor.
 *
 * <p>The admin client is declared explicitly because topic provisioning is the only
 * operation this service performs outside of Kafka Streams. Kafka Streams builds its own
 * clients from {@code spring.kafka.streams.*}.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaClientConfiguration {

    /** Used for topic provisioning and by the Kafka health indicator. */
    @Bean(destroyMethod = "close")
    public Admin kafkaAdminClient(KafkaProperties kafkaProperties) {
        return AdminClient.create(kafkaProperties.buildAdminProperties(null));
    }
}

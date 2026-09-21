package com.coobi.logistics.eventgenerator.config;

import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Kafka clients used by the generator.
 *
 * <p>Both clients are created from {@code spring.kafka.*} configuration, which keeps a
 * single place that documents the connection and the producer tuning. The producer and
 * the template are declared explicitly as {@code String} keyed types: the payload is a
 * JSON document serialized by the application, so the generic parameters are part of the
 * contract rather than a guess.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaClientConfiguration {

    /** Used for topic provisioning and by the Kafka health indicator. */
    @Bean(destroyMethod = "close")
    public Admin kafkaAdminClient(KafkaProperties kafkaProperties) {
        return AdminClient.create(kafkaProperties.buildAdminProperties(null));
    }

    /**
     * The producer of the telemetry topic.
     *
     * <p>The factory is declared explicitly - rather than left to the auto-configuration - to
     * pin the serializers to the JSON contract. That declaration also replaces the factory the
     * auto-configuration would have built, so the customizers it applies to that factory have
     * to be applied here as well, or the producer metrics of MVP-8.3 (rates, errors, in-flight
     * requests) would be silently missing from this service.
     */
    @Bean
    public ProducerFactory<String, String> telemetryProducerFactory(
            KafkaProperties kafkaProperties,
            ObjectProvider<DefaultKafkaProducerFactoryCustomizer> producerFactoryCustomizers) {
        Map<String, Object> producerProperties = kafkaProperties.buildProducerProperties(null);
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        DefaultKafkaProducerFactory<String, String> producerFactory =
                new DefaultKafkaProducerFactory<>(producerProperties);
        producerFactoryCustomizers.orderedStream().forEach(customizer -> customizer.customize(producerFactory));
        return producerFactory;
    }

    @Bean
    public KafkaTemplate<String, String> telemetryKafkaTemplate(
            ProducerFactory<String, String> telemetryProducerFactory) {
        return new KafkaTemplate<>(telemetryProducerFactory);
    }
}

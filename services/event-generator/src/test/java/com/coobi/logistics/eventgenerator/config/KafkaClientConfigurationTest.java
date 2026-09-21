package com.coobi.logistics.eventgenerator.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;

/**
 * MVP-8.3: the producer of this service keeps the customizers of the auto-configuration.
 *
 * <p>The factory is declared by this service instead of being left to Spring Boot, and Boot
 * applies its producer customizers - among them the Micrometer binder that publishes the Kafka
 * client metrics - only to the factory it builds itself. This test pins the replacement: the
 * customizers of the context are applied to the declared factory, in order, and the serializers
 * of the JSON contract survive it.
 */
class KafkaClientConfigurationTest {

    @Test
    void appliesTheProducerFactoryCustomizersOfTheContext() {
        List<String> applied = new ArrayList<>();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("first", (DefaultKafkaProducerFactoryCustomizer) factory -> applied.add("first"));
        beans.registerSingleton("second", (DefaultKafkaProducerFactoryCustomizer) factory -> applied.add("second"));

        ProducerFactory<String, String> factory = new KafkaClientConfiguration()
                .telemetryProducerFactory(
                        new KafkaProperties(), beans.getBeanProvider(DefaultKafkaProducerFactoryCustomizer.class));

        assertThat(factory).isInstanceOf(DefaultKafkaProducerFactory.class);
        assertThat(applied).containsExactly("first", "second");
    }

    @Test
    void keepsTheJsonSerializersOfTheProducerContract() {
        ProducerFactory<String, String> factory = new KafkaClientConfiguration()
                .telemetryProducerFactory(new KafkaProperties(), emptyProvider());

        assertThat(((DefaultKafkaProducerFactory<?, ?>) factory).getConfigurationProperties())
                .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    }

    /** The provider of a context that declares no producer customizer at all. */
    private static ObjectProvider<DefaultKafkaProducerFactoryCustomizer> emptyProvider() {
        return new DefaultListableBeanFactory()
                .getBeanProvider(DefaultKafkaProducerFactoryCustomizer.class);
    }
}

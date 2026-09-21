package com.coobi.logistics.streamprocessor.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** MVP-2.2 and MVP-2.3: the topics match the roadmap and every name can be overridden. */
class KafkaTopicsPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    void bindsTheDocumentedTopicTopology() {
        runner.run(context -> {
            KafkaTopicsProperties properties = context.getBean(KafkaTopicsProperties.class);

            assertThat(properties.locationTopic()).isEqualTo("logistics.vehicle.location.v1");
            assertThat(properties.deadLetterTopic()).isEqualTo("logistics.vehicle.location.dlq.v1");
            assertThat(properties.alertTopic()).isEqualTo("logistics.alert.v1");
            assertThat(properties.topicSpecs()).extracting(spec -> spec.name()).containsExactly(
                    "logistics.vehicle.location.v1",
                    "logistics.vehicle.location.dlq.v1",
                    "logistics.alert.v1");
            assertThat(properties.topicSpecs()).allSatisfy(spec -> {
                assertThat(spec.partitions()).isEqualTo(6);
                assertThat(spec.replicationFactor()).isEqualTo((short) 1);
            });
            assertThat(properties.getInitialization().isEnabled()).isTrue();
        });
    }

    @Test
    void bindsTopicOverrides() {
        runner.withPropertyValues(
                        "coobi.kafka.topics.alert=logistics.alert.test.v1",
                        "coobi.kafka.topics.partitions=3",
                        "coobi.kafka.initialization.enabled=false")
                .run(context -> {
                    KafkaTopicsProperties properties = context.getBean(KafkaTopicsProperties.class);

                    assertThat(properties.alertTopic()).isEqualTo("logistics.alert.test.v1");
                    assertThat(properties.getTopics().getPartitions()).isEqualTo(3);
                    assertThat(properties.getInitialization().isEnabled()).isFalse();
                });
    }

    @Test
    void failsFastWhenATopicNameIsBlankOrTheTopologyIsInvalid() {
        runner.withPropertyValues("coobi.kafka.topics.alert= ").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("coobi.kafka.topics.partitions=0").run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KafkaTopicsProperties.class)
    static class PropertiesRegistration {
    }
}

package com.coobi.logistics.eventgenerator.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * MVP-1.1/MVP-1.3: the offline switch documented in `.env.example` really binds.
 *
 * <p>`COOBI_KAFKA_INITIALIZATION_ENABLED` is the environment-variable form of
 * `coobi.kafka.initialization.enabled`, and it is the switch that lets the service start
 * without a broker. Relaxed binding maps a canonical name to the environment name, so
 * the assertion runs against a real environment property source rather than a plain map,
 * which relaxed binding cannot translate.
 */
class KafkaTopicsPropertiesBindingTest {

    @Test
    void bindsTheOfflineSwitchFromItsEnvironmentVariableForm() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("COOBI_KAFKA_INITIALIZATION_ENABLED", "false")));

        KafkaTopicsProperties properties = Binder.get(environment)
                .bind("coobi.kafka", Bindable.of(KafkaTopicsProperties.class))
                .get();

        assertThat(properties.getInitialization().isEnabled()).isFalse();
        assertThat(properties.getTopics().getPartitions()).isEqualTo(6);
    }
}

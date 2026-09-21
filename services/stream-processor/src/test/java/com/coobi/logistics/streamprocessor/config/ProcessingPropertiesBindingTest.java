package com.coobi.logistics.streamprocessor.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** MVP-2.3: the speed limit binds and fails fast when it is not a usable threshold. */
class ProcessingPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    void bindsTheDocumentedDefault() {
        runner.run(context -> {
            ProcessingProperties properties = context.getBean(ProcessingProperties.class);

            assertThat(properties.getSpeedLimitKph()).isEqualTo(120.0);
        });
    }

    @Test
    void bindsAnOverride() {
        runner.withPropertyValues("coobi.processing.speed-limit-kph=90.5")
                .run(context -> {
                    ProcessingProperties properties = context.getBean(ProcessingProperties.class);

                    assertThat(properties.getSpeedLimitKph()).isEqualTo(90.5);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "NaN"})
    void failsFastWhenTheSpeedLimitIsNotAUsableThreshold(String speedLimit) {
        runner.withPropertyValues("coobi.processing.speed-limit-kph=" + speedLimit)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProcessingProperties.class)
    static class PropertiesRegistration {
    }
}

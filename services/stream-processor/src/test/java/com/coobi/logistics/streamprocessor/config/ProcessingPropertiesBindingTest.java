package com.coobi.logistics.streamprocessor.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * MVP-2.3 and MVP-3.2: the detection thresholds bind and fail fast when a value is not a
 * usable threshold.
 */
class ProcessingPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    void bindsTheDocumentedDefaults() {
        runner.run(context -> {
            ProcessingProperties properties = context.getBean(ProcessingProperties.class);

            assertThat(properties.getSpeedLimitKph()).isEqualTo(120.0);
            assertThat(properties.getStoppedWindowSeconds()).isEqualTo(300L);
            assertThat(properties.getMovementThresholdMeters()).isEqualTo(50.0);
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

    @Test
    void bindsTheStoppedOverrides() {
        runner.withPropertyValues(
                        "coobi.processing.stopped-window-seconds=60",
                        "coobi.processing.movement-threshold-meters=200.5")
                .run(context -> {
                    ProcessingProperties properties = context.getBean(ProcessingProperties.class);

                    assertThat(properties.getStoppedWindowSeconds()).isEqualTo(60L);
                    assertThat(properties.getMovementThresholdMeters()).isEqualTo(200.5);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "NaN"})
    void failsFastWhenTheSpeedLimitIsNotAUsableThreshold(String speedLimit) {
        runner.withPropertyValues("coobi.processing.speed-limit-kph=" + speedLimit)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-300"})
    void failsFastWhenTheStoppedWindowIsNotAUsableDuration(String stoppedWindow) {
        runner.withPropertyValues("coobi.processing.stopped-window-seconds=" + stoppedWindow)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-50", "NaN"})
    void failsFastWhenTheMovementThresholdIsNotAUsableThreshold(String movementThreshold) {
        runner.withPropertyValues("coobi.processing.movement-threshold-meters=" + movementThreshold)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProcessingProperties.class)
    static class PropertiesRegistration {
    }
}

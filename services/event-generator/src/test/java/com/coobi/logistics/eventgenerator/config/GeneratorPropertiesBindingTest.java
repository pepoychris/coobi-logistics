package com.coobi.logistics.eventgenerator.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** MVP-1.1: property binding and fail-fast behaviour, without starting Kafka. */
class GeneratorPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    void bindsTheDocumentedDefaultsWhenNothingIsConfigured() {
        runner.run(context -> {
            GeneratorProperties properties = context.getBean(GeneratorProperties.class);

            assertThat(properties.getMode()).isEqualTo(GeneratorMode.NORMAL);
            assertThat(properties.effectiveVehicleCount()).isEqualTo(1000);
            assertThat(properties.effectiveTargetEventsPerSecond()).isEqualTo(1000);
        });
    }

    @Test
    void bindsOverridesAndSelectsTheActiveMode() {
        runner.withPropertyValues(
                        "coobi.generator.mode=LOAD_TEST",
                        "coobi.generator.vehicle-count=2500",
                        "coobi.generator.target-events-per-second=5000",
                        "coobi.generator.load-test.vehicle-count=3000",
                        "coobi.generator.load-test.target-events-per-second=4000",
                        "coobi.generator.simulation.max-speed-kph=90")
                .run(context -> {
                    GeneratorProperties properties = context.getBean(GeneratorProperties.class);

                    assertThat(properties.getMode()).isEqualTo(GeneratorMode.LOAD_TEST);
                    assertThat(properties.getVehicleCount()).isEqualTo(2500);
                    assertThat(properties.effectiveVehicleCount()).isEqualTo(3000);
                    assertThat(properties.effectiveTargetEventsPerSecond()).isEqualTo(4000);
                    assertThat(properties.getSimulation().getMaxSpeedKph()).isEqualTo(90.0);
                });
    }

    @Test
    void failsFastWhenAVehicleCountIsInvalid() {
        runner.withPropertyValues("coobi.generator.vehicle-count=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsFastWhenTheModeIsUnknown() {
        runner.withPropertyValues("coobi.generator.mode=STRESS_TEST")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsFastWhenTheSimulationAreaIsInvalid() {
        runner.withPropertyValues("coobi.generator.simulation.center-latitude=120.0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GeneratorProperties.class)
    static class PropertiesRegistration {
    }
}

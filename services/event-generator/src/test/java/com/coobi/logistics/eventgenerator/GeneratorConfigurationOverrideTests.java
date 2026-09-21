package com.coobi.logistics.eventgenerator;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.eventgenerator.config.GeneratorMode;
import com.coobi.logistics.eventgenerator.config.GeneratorProperties;
import com.coobi.logistics.eventgenerator.simulation.VehicleTelemetrySimulator;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * MVP-1.1/MVP-1.5: the documented environment variables override the shipped defaults.
 *
 * <p>The property names below are the ones documented in {@code .env.example}: the
 * {@code application.yml} placeholders resolve them from the environment, exactly as an
 * exported shell variable would.
 */
@SpringBootTest(properties = {
    "KAFKA_BOOTSTRAP_SERVERS=localhost:19092",
    "VEHICLE_COUNT=2500",
    "TARGET_EVENTS_PER_SECOND=5000",
    "GENERATOR_MODE=LOAD_TEST",
    "LOAD_TEST_VEHICLE_COUNT=3000",
    "LOAD_TEST_TARGET_EVENTS_PER_SECOND=4000",
    "LOAD_TEST_DURATION=5m",
    "GENERATOR_PUBLISH_ENABLED=false",
    "coobi.kafka.initialization.enabled=false"
})
class GeneratorConfigurationOverrideTests {

    @Autowired
    private GeneratorProperties generatorProperties;

    @Autowired
    private VehicleTelemetrySimulator simulator;

    @Test
    void honoursTheDocumentedEnvironmentVariables() {
        assertThat(generatorProperties.getMode()).isEqualTo(GeneratorMode.LOAD_TEST);
        assertThat(generatorProperties.getVehicleCount()).isEqualTo(2500);
        assertThat(generatorProperties.getTargetEventsPerSecond()).isEqualTo(5000);
        assertThat(generatorProperties.effectiveVehicleCount()).isEqualTo(3000);
        assertThat(generatorProperties.effectiveTargetEventsPerSecond()).isEqualTo(4000);
        assertThat(generatorProperties.effectivePublishingDuration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(simulator.vehicleCount()).isEqualTo(3000);
    }
}

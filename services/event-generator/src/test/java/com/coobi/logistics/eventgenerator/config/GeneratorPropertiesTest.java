package com.coobi.logistics.eventgenerator.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** MVP-1.1/MVP-1.5: defaults, mode resolution and fail-fast validation of the config. */
class GeneratorPropertiesTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void shipsWithTheDocumentedNormalModeDefaults() {
        GeneratorProperties properties = new GeneratorProperties();

        assertThat(properties.getMode()).isEqualTo(GeneratorMode.NORMAL);
        assertThat(properties.getVehicleCount()).isEqualTo(1000);
        assertThat(properties.getTargetEventsPerSecond()).isEqualTo(1000);
        assertThat(properties.isPublishEnabled()).isTrue();
        assertThat(properties.effectiveVehicleCount()).isEqualTo(1000);
        assertThat(properties.effectiveTargetEventsPerSecond()).isEqualTo(1000);
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void normalModeIgnoresTheLoadTestValues() {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setVehicleCount(1500);
        properties.setTargetEventsPerSecond(2500);
        properties.getLoadTest().setVehicleCount(5000);
        properties.getLoadTest().setTargetEventsPerSecond(20_000);

        assertThat(properties.effectiveVehicleCount()).isEqualTo(1500);
        assertThat(properties.effectiveTargetEventsPerSecond()).isEqualTo(2500);
    }

    @Test
    void loadTestModeUsesTheLoadTestValues() {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setMode(GeneratorMode.LOAD_TEST);
        properties.getLoadTest().setVehicleCount(8000);
        properties.getLoadTest().setTargetEventsPerSecond(40_000);

        assertThat(properties.effectiveVehicleCount()).isEqualTo(8000);
        assertThat(properties.effectiveTargetEventsPerSecond()).isEqualTo(40_000);
    }

    @Test
    void rejectsOutOfRangeValues() {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setVehicleCount(0);
        properties.setTargetEventsPerSecond(0);
        properties.setTickIntervalMillis(1);
        properties.getSimulation().setCenterLatitude(120.0);
        properties.getSimulation().setMaxSpeedDeltaKph(-1.0);

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("vehicleCount", "targetEventsPerSecond", "tickIntervalMillis")
                .anySatisfy(path -> assertThat(path).startsWith("simulation"));
    }
}

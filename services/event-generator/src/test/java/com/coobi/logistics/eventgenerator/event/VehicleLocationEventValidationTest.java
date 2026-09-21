package com.coobi.logistics.eventgenerator.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** MVP-1.2: the validation rules of the location event contract. */
class VehicleLocationEventValidationTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-09-21T09:15:00.123Z");

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
    void acceptsAValidEvent() {
        assertThat(violatedProperties(validEvent())).isEmpty();
    }

    @Test
    void acceptsTheBoundaryValuesOfTheContract() {
        assertThat(violatedProperties(eventWith(90.0, -180.0, 0.0, 359.999))).isEmpty();
        assertThat(violatedProperties(eventWith(-90.0, 180.0, 380.0, 0.0))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(doubles = {90.1, -90.1})
    void rejectsLatitudeOutOfRange(double latitude) {
        assertThat(violatedProperties(eventWith(latitude, -0.3763, 60.0, 214.5))).contains("data.latitude");
    }

    @ParameterizedTest
    @ValueSource(doubles = {180.1, -180.1})
    void rejectsLongitudeOutOfRange(double longitude) {
        assertThat(violatedProperties(eventWith(39.4699, longitude, 60.0, 214.5))).contains("data.longitude");
    }

    @Test
    void rejectsNegativeSpeed() {
        assertThat(violatedProperties(eventWith(39.4699, -0.3763, -0.1, 214.5))).contains("data.speed");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 360.0})
    void rejectsHeadingOutOfRange(double heading) {
        assertThat(violatedProperties(eventWith(39.4699, -0.3763, 60.0, heading))).contains("data.heading");
    }

    @Test
    void rejectsBlankVehicleId() {
        VehicleLocationEvent event = new VehicleLocationEvent(
                java.util.UUID.randomUUID(),
                VehicleLocationEvent.EVENT_TYPE_VEHICLE_LOCATION_UPDATED,
                VehicleLocationEvent.CURRENT_VERSION,
                "  ",
                TIMESTAMP,
                new VehicleLocationData(39.4699, -0.3763, 60.0, 214.5));

        assertThat(violatedProperties(event)).contains("vehicleId");
    }

    @Test
    void rejectsNonFiniteNumbers() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new VehicleLocationData(Double.NaN, -0.3763, 60.0, 214.5))
                .withMessageContaining("latitude");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new VehicleLocationData(39.4699, Double.POSITIVE_INFINITY, 60.0, 214.5))
                .withMessageContaining("longitude");
    }

    @Test
    void rejectsNullRequiredComponents() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new VehicleLocationEvent(
                        null,
                        VehicleLocationEvent.EVENT_TYPE_VEHICLE_LOCATION_UPDATED,
                        VehicleLocationEvent.CURRENT_VERSION,
                        "TRUCK-00001",
                        TIMESTAMP,
                        validData()))
                .withMessageContaining("eventId");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new VehicleLocationEvent(
                        java.util.UUID.randomUUID(),
                        VehicleLocationEvent.EVENT_TYPE_VEHICLE_LOCATION_UPDATED,
                        VehicleLocationEvent.CURRENT_VERSION,
                        "TRUCK-00001",
                        null,
                        validData()))
                .withMessageContaining("timestamp");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new VehicleLocationEvent(
                        java.util.UUID.randomUUID(),
                        VehicleLocationEvent.EVENT_TYPE_VEHICLE_LOCATION_UPDATED,
                        VehicleLocationEvent.CURRENT_VERSION,
                        "TRUCK-00001",
                        TIMESTAMP,
                        null))
                .withMessageContaining("data");
    }

    @Test
    void rejectsAnUnknownSchemaVersionOrEventType() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new VehicleLocationEvent(
                        java.util.UUID.randomUUID(),
                        VehicleLocationEvent.EVENT_TYPE_VEHICLE_LOCATION_UPDATED,
                        2,
                        "TRUCK-00001",
                        TIMESTAMP,
                        validData()))
                .withMessageContaining("version");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new VehicleLocationEvent(
                        java.util.UUID.randomUUID(),
                        "VEHICLE_ENGINE_STARTED",
                        VehicleLocationEvent.CURRENT_VERSION,
                        "TRUCK-00001",
                        TIMESTAMP,
                        validData()))
                .withMessageContaining("eventType");
    }

    @Test
    void factoryCreatesTheCurrentVersionWithUniqueIdentifiers() {
        VehicleLocationEvent first = VehicleLocationEvent.create("TRUCK-00001", TIMESTAMP, validData());
        VehicleLocationEvent second = VehicleLocationEvent.create("TRUCK-00001", TIMESTAMP, validData());

        assertThat(first.eventType()).isEqualTo("VEHICLE_LOCATION_UPDATED");
        assertThat(first.version()).isEqualTo(1);
        assertThat(violatedProperties(first)).isEmpty();
        assertThat(first.eventId()).isNotEqualTo(second.eventId());
    }

    private static Set<String> violatedProperties(VehicleLocationEvent event) {
        return validator.validate(event).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static VehicleLocationEvent validEvent() {
        return VehicleLocationEvent.create("TRUCK-00001", TIMESTAMP, validData());
    }

    private static VehicleLocationEvent eventWith(double latitude, double longitude, double speed, double heading) {
        return VehicleLocationEvent.create(
                "TRUCK-00001", TIMESTAMP, new VehicleLocationData(latitude, longitude, speed, heading));
    }

    private static VehicleLocationData validData() {
        return new VehicleLocationData(39.4699, -0.3763, 82.3, 214.5);
    }
}

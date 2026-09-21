package com.coobi.logistics.streamprocessor.support;

import com.coobi.logistics.streamprocessor.event.VehicleLocationData;
import com.coobi.logistics.streamprocessor.event.VehicleLocationEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Payloads and collaborators shared by the processing tests.
 *
 * <p>The mapper is built the way Spring Boot builds its own: well-known modules registered
 * (so {@link Instant} is written as an ISO-8601 string) and unknown properties ignored.
 */
public final class TelemetryFixtures {

    public static final Instant TIMESTAMP = Instant.parse("2026-09-21T09:15:00.123Z");

    /** Coordinates of the demonstration trajectory (Valencia, Spain). */
    public static final double DEMO_LATITUDE = 39.4699;

    public static final double DEMO_LONGITUDE = -0.3763;

    /** Heading of the demonstration trajectory, in degrees clockwise from north. */
    public static final double DEMO_HEADING = 214.5;

    private static ValidatorFactory validatorFactory;

    private TelemetryFixtures() {
    }

    public static ObjectMapper objectMapper() {
        // Spring Boot writes java.time values as ISO-8601 strings by default (Global Rule
        // 12); the fixture mapper states the same expectation explicitly, so the timeline
        // tests do not depend on the builder defaults.
        return Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    public static Validator validator() {
        if (validatorFactory == null) {
            validatorFactory = Validation.buildDefaultValidatorFactory();
        }
        return validatorFactory.getValidator();
    }

    public static void closeValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
            validatorFactory = null;
        }
    }

    /** A version-1 location event with the demonstration coordinates. */
    public static String payload(String vehicleId, double speed) {
        return payload(vehicleId, TIMESTAMP, speed, DEMO_LATITUDE, DEMO_LONGITUDE);
    }

    /**
     * A version-1 location event with explicit coordinates. The event is built directly
     * instead of through the factory so a test can produce a payload that is structurally
     * valid but violates the contract (for example a latitude of 91 degrees).
     */
    public static String payload(String vehicleId, double speed, double latitude, double longitude) {
        return payload(vehicleId, TIMESTAMP, speed, latitude, longitude);
    }

    /**
     * A version-1 location event at an explicit instant, so a test can drive a five-minute
     * stopped window with telemetry of its own timeline instead of waiting.
     */
    public static String payload(
            String vehicleId, Instant timestamp, double speed, double latitude, double longitude) {
        return serialize(new VehicleLocationEvent(
                UUID.randomUUID(),
                VehicleLocationEvent.EVENT_TYPE_VEHICLE_LOCATION_UPDATED,
                VehicleLocationEvent.CURRENT_VERSION,
                vehicleId,
                timestamp,
                new VehicleLocationData(latitude, longitude, speed, DEMO_HEADING)));
    }

    public static String serialize(Object value) {
        try {
            return objectMapper().writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("serialization failed", failure);
        }
    }
}

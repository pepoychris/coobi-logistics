package com.coobi.logistics.streamprocessor.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.coobi.logistics.streamprocessor.support.Immutability;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/** MVP-3.1: serialized shape and immutability of the per-vehicle state document. */
@JsonTest
class VehicleStateJsonTest {

    private static final Instant LAST_UPDATE = Instant.parse("2026-09-21T09:15:00.123Z");
    private static final Instant WINDOW_STARTED_AT = Instant.parse("2026-09-21T09:10:00.000Z");

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void serializesTheDocumentedContract() {
        VehicleState state = new VehicleState(
                39.4699, -0.3763, 0.0, 214.5, LAST_UPDATE, VehicleStatus.MOVING, WINDOW_STARTED_AT, 12.5);

        assertThat(write(state))
                .isEqualTo("{\"latitude\":39.4699,\"longitude\":-0.3763,\"speed\":0.0,\"heading\":214.5,"
                        + "\"lastUpdate\":\"2026-09-21T09:15:00.123Z\",\"status\":\"MOVING\","
                        + "\"windowStartedAt\":\"2026-09-21T09:10:00Z\",\"accumulatedMeters\":12.5}");
    }

    @Test
    void roundTripsWithoutLosingInformation() throws Exception {
        VehicleState original = new VehicleState(
                39.4699, -0.3763, 12.5, 214.5, LAST_UPDATE, VehicleStatus.STOPPED, WINDOW_STARTED_AT, 42.25);

        VehicleState restored = objectMapper.readValue(write(original), VehicleState.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void stateIsImmutable() {
        assertThat(Immutability.isRecordWithFinalFields(VehicleState.class)).isTrue();
        assertThat(Immutability.setterLikeMethods(VehicleState.class)).isEmpty();
        assertThat(Immutability.componentTypesAreImmutable(
                        VehicleState.class, List.of(Instant.class, VehicleStatus.class)))
                .isTrue();
    }

    @Test
    void rejectsNonFiniteValuesAndANegativeAccumulatedDistance() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> state(Double.NaN, -0.3763, 0.0, 214.5, 0.0))
                .withMessageContaining("latitude");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> state(39.4699, -0.3763, Double.POSITIVE_INFINITY, 214.5, 0.0))
                .withMessageContaining("speed");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> state(39.4699, -0.3763, 0.0, 214.5, -0.1))
                .withMessageContaining("accumulatedMeters");
    }

    @Test
    void rejectsNullInstantsAndANullStatus() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new VehicleState(
                        39.4699, -0.3763, 0.0, 214.5, null, VehicleStatus.MOVING, WINDOW_STARTED_AT, 0.0))
                .withMessageContaining("lastUpdate");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new VehicleState(
                        39.4699, -0.3763, 0.0, 214.5, LAST_UPDATE, null, WINDOW_STARTED_AT, 0.0))
                .withMessageContaining("status");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new VehicleState(
                        39.4699, -0.3763, 0.0, 214.5, LAST_UPDATE, VehicleStatus.MOVING, null, 0.0))
                .withMessageContaining("windowStartedAt");
    }

    private static VehicleState state(double latitude, double longitude, double speed, double heading, double metres) {
        return new VehicleState(
                latitude, longitude, speed, heading, LAST_UPDATE, VehicleStatus.MOVING, WINDOW_STARTED_AT, metres);
    }

    private String write(VehicleState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception failure) {
            throw new IllegalStateException("serialization failed", failure);
        }
    }
}

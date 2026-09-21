package com.coobi.logistics.eventgenerator.simulation;

import static com.coobi.logistics.eventgenerator.support.SimulationFixtures.settings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** MVP-1.4: the simulator validates its own inputs, independently of Spring. */
class VehicleSimulationSettingsTest {

    @Test
    void acceptsConsistentSettings() {
        assertThat(settings("TRUCK-", 1000, 0.0, 110.0).vehicleCount()).isEqualTo(1000);
    }

    @Test
    void rejectsBlankVehicleIdPrefix() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> settings(" ", 1000, 0.0, 110.0))
                .withMessageContaining("vehicleIdPrefix");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveVehicleCount(int vehicleCount) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> settings("TRUCK-", vehicleCount, 0.0, 110.0))
                .withMessageContaining("vehicleCount");
    }

    @Test
    void rejectsAnAreaOutsideTheGlobe() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new VehicleSimulationSettings(
                        "TRUCK-", 10, 1L, 86.0, 0.0, 10.0, 0.0, 100.0, 5.0, 12.0, 10.0))
                .withMessageContaining("centerLatitude");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new VehicleSimulationSettings(
                        "TRUCK-", 10, 1L, 39.0, 181.0, 10.0, 0.0, 100.0, 5.0, 12.0, 10.0))
                .withMessageContaining("centerLongitude");
    }

    @Test
    void rejectsAnInvertedSpeedRange() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> settings("TRUCK-", 10, 120.0, 60.0))
                .withMessageContaining("maxSpeedKph");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.0})
    void rejectsNonPositiveAreaRadius(double radius) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new VehicleSimulationSettings(
                        "TRUCK-", 10, 1L, 39.0, 0.0, radius, 0.0, 100.0, 5.0, 12.0, 10.0))
                .withMessageContaining("areaRadiusKilometres");
    }

    @Test
    void rejectsAnImpossibleHeadingChange() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new VehicleSimulationSettings(
                        "TRUCK-", 10, 1L, 39.0, 0.0, 10.0, 0.0, 100.0, 5.0, 181.0, 10.0))
                .withMessageContaining("maxHeadingDeltaDegrees");
    }

    @Test
    void rejectsNonPositiveMaximumElapsedTime() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new VehicleSimulationSettings(
                        "TRUCK-", 10, 1L, 39.0, 0.0, 10.0, 0.0, 100.0, 5.0, 12.0, 0.0))
                .withMessageContaining("maxElapsedSeconds");
    }

}

package com.coobi.logistics.streamprocessor.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.within;

import com.coobi.logistics.streamprocessor.event.VehicleLocationData;
import com.coobi.logistics.streamprocessor.event.VehicleLocationEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * MVP-3.2: the {@code MOVING} to {@code STOPPED} state machine, driven by telemetry
 * timestamps instead of the clock.
 *
 * <p>Every test states its own timeline, so the whole five-minute window is covered
 * deterministically and without waiting.
 */
class StoppedVehicleDetectorTest {

    private static final String TRUCK = "TRUCK-00001";
    private static final Instant START = Instant.parse("2026-09-21T09:15:00Z");

    private static final double LATITUDE = 39.4699;
    private static final double LONGITUDE = -0.3763;

    /** 10.01 m north of the demonstration position. */
    private static final double TEN_METERS_NORTH = 39.46999;

    /** 20.02 m north of the demonstration position. */
    private static final double TWENTY_METERS_NORTH = 39.47008;

    /** 40.03 m north of the demonstration position. */
    private static final double FORTY_METERS_NORTH = 39.47026;

    /** 60.05 m north of the demonstration position. */
    private static final double SIXTY_METERS_NORTH = 39.47044;

    /** 122.31 m north of the demonstration position. */
    private static final double ONE_HUNDRED_TWENTY_METERS_NORTH = 39.471;

    private final StoppedVehicleDetector detector = new StoppedVehicleDetector(Duration.ofSeconds(300), 50.0);

    @Test
    void startsMovingWithTheFirstEventOfAVehicle() {
        VehicleState state = detector.advance(null, event(START, 80.0, LATITUDE, LONGITUDE));

        assertThat(state.status()).isEqualTo(VehicleStatus.MOVING);
        assertThat(state.latitude()).isEqualTo(LATITUDE);
        assertThat(state.longitude()).isEqualTo(LONGITUDE);
        assertThat(state.speed()).isEqualTo(80.0);
        assertThat(state.heading()).isEqualTo(300.0);
        assertThat(state.lastUpdate()).isEqualTo(START);
        assertThat(state.windowStartedAt()).isEqualTo(START);
        assertThat(state.accumulatedMeters()).isEqualTo(0.0);
    }

    @Test
    void reportsTheVehicleStoppedOnceTheWindowElapsedWithoutMovement() {
        VehicleState state = state(START, 0.0, LATITUDE, LONGITUDE);

        state = detector.advance(state, event(START.plusSeconds(120), 0.0, LATITUDE, LONGITUDE));
        assertThat(state.status()).isEqualTo(VehicleStatus.MOVING);

        state = detector.advance(state, event(START.plusSeconds(240), 0.0, LATITUDE, LONGITUDE));
        assertThat(state.status()).isEqualTo(VehicleStatus.MOVING);

        state = detector.advance(state, event(START.plusSeconds(299), 0.0, LATITUDE, LONGITUDE));
        assertThat(state.status()).as("one second before the window has elapsed").isEqualTo(VehicleStatus.MOVING);

        state = detector.advance(state, event(START.plusSeconds(300), 0.0, LATITUDE, LONGITUDE));
        assertThat(state.status()).isEqualTo(VehicleStatus.STOPPED);
        assertThat(state.windowStartedAt()).isEqualTo(START);
        assertThat(state.accumulatedMeters()).isEqualTo(0.0);
        assertThat(state.lastUpdate()).isEqualTo(START.plusSeconds(300));
    }

    @Test
    void staysStoppedWhileTheVehicleDoesNotMove() {
        VehicleState state = state(START, 0.0, LATITUDE, LONGITUDE);
        state = detector.advance(state, event(START.plusSeconds(300), 0.0, LATITUDE, LONGITUDE));
        assertThat(state.status()).isEqualTo(VehicleStatus.STOPPED);

        state = detector.advance(state, event(START.plusSeconds(600), 0.0, LATITUDE, LONGITUDE));
        state = detector.advance(state, event(START.plusSeconds(900), 0.0, LATITUDE, LONGITUDE));

        assertThat(state.status()).as("remaining stopped is not a new transition").isEqualTo(VehicleStatus.STOPPED);
        assertThat(state.windowStartedAt()).isEqualTo(START);
    }

    @Test
    void restartsTheWindowWhenTheVehicleDrivesAway() {
        VehicleState state = state(START, 0.0, LATITUDE, LONGITUDE);
        state = detector.advance(state, event(START.plusSeconds(300), 0.0, LATITUDE, LONGITUDE));
        assertThat(state.status()).isEqualTo(VehicleStatus.STOPPED);

        Instant departure = START.plusSeconds(360);
        state = detector.advance(state, event(departure, 25.0, ONE_HUNDRED_TWENTY_METERS_NORTH, LONGITUDE));

        assertThat(state.status()).isEqualTo(VehicleStatus.MOVING);
        assertThat(state.windowStartedAt()).isEqualTo(departure);
        assertThat(state.accumulatedMeters()).isEqualTo(0.0);
        assertThat(state.latitude()).isEqualTo(ONE_HUNDRED_TWENTY_METERS_NORTH);
        assertThat(state.speed()).isEqualTo(25.0);
    }

    @Test
    void countsCrawlingBelowTheThresholdAsNoMovement() {
        VehicleState state = state(START, 0.0, LATITUDE, LONGITUDE);
        state = detector.advance(state, event(START.plusSeconds(60), 0.5, TEN_METERS_NORTH, LONGITUDE));

        state = detector.advance(state, event(START.plusSeconds(300), 0.5, TEN_METERS_NORTH, LONGITUDE));

        assertThat(state.status()).isEqualTo(VehicleStatus.STOPPED);
        assertThat(state.accumulatedMeters()).isCloseTo(10.01, within(0.1));
    }

    @Test
    void restartsTheWindowWhenTheAccumulatedDistanceReachesTheThreshold() {
        VehicleState state = state(START, 0.0, LATITUDE, LONGITUDE);

        // Three steps of about twenty metres: the third one takes the accumulated distance
        // over the fifty-metre threshold, so the window starts again there.
        state = detector.advance(state, event(START.plusSeconds(60), 1.0, TWENTY_METERS_NORTH, LONGITUDE));
        state = detector.advance(state, event(START.plusSeconds(120), 1.0, FORTY_METERS_NORTH, LONGITUDE));
        assertThat(state.status()).isEqualTo(VehicleStatus.MOVING);
        assertThat(state.windowStartedAt()).isEqualTo(START);

        Instant restart = START.plusSeconds(180);
        state = detector.advance(state, event(restart, 1.0, SIXTY_METERS_NORTH, LONGITUDE));
        assertThat(state.windowStartedAt()).isEqualTo(restart);
        assertThat(state.accumulatedMeters()).isEqualTo(0.0);

        // The original window has long elapsed, but the current one has not.
        state = detector.advance(state, event(restart.plusSeconds(240), 0.0, SIXTY_METERS_NORTH, LONGITUDE));
        assertThat(state.status()).isEqualTo(VehicleStatus.MOVING);

        state = detector.advance(state, event(restart.plusSeconds(300), 0.0, SIXTY_METERS_NORTH, LONGITUDE));
        assertThat(state.status()).isEqualTo(VehicleStatus.STOPPED);
    }

    @Test
    void keepsTheWindowMovingForwardWhenALateEventArrives() {
        VehicleState state = state(START, 0.0, LATITUDE, LONGITUDE);

        // A record that arrives out of order carries an older timestamp; it updates the
        // latest position but cannot make the window look older than it is.
        state = detector.advance(state, event(START.plusSeconds(600), 0.0, LATITUDE, LONGITUDE));
        assertThat(state.status()).isEqualTo(VehicleStatus.STOPPED);

        state = detector.advance(state, event(START.plusSeconds(30), 0.0, LATITUDE, LONGITUDE));
        assertThat(state.status()).isEqualTo(VehicleStatus.STOPPED);
        assertThat(state.lastUpdate()).isEqualTo(START.plusSeconds(30));
    }

    @Test
    void reportsTheStateOfTheLatestEvent() {
        VehicleState state = state(START, 80.0, LATITUDE, LONGITUDE);
        Instant later = START.plusSeconds(10);

        state = detector.advance(state, event(later, 95.5, TWENTY_METERS_NORTH, LONGITUDE));

        assertThat(state.latitude()).isEqualTo(TWENTY_METERS_NORTH);
        assertThat(state.speed()).isEqualTo(95.5);
        assertThat(state.heading()).isEqualTo(300.0);
        assertThat(state.lastUpdate()).isEqualTo(later);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "-PT5M"})
    void rejectsAWindowThatCannotMeasureAnything(String window) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new StoppedVehicleDetector(Duration.parse(window), 50.0))
                .withMessageContaining("window");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.0, Double.NaN})
    void rejectsAMovementThresholdThatCannotMeasureAnything(double threshold) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new StoppedVehicleDetector(Duration.ofSeconds(300), threshold))
                .withMessageContaining("movementThresholdMeters");
    }

    private VehicleState state(Instant timestamp, double speed, double latitude, double longitude) {
        return detector.advance(null, event(timestamp, speed, latitude, longitude));
    }

    private static VehicleLocationEvent event(Instant timestamp, double speed, double latitude, double longitude) {
        return new VehicleLocationEvent(
                UUID.randomUUID(),
                VehicleLocationEvent.EVENT_TYPE_VEHICLE_LOCATION_UPDATED,
                VehicleLocationEvent.CURRENT_VERSION,
                TRUCK,
                timestamp,
                new VehicleLocationData(latitude, longitude, speed, 300.0));
    }
}

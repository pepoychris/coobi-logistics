package com.coobi.logistics.eventgenerator.simulation;

import static com.coobi.logistics.eventgenerator.support.SimulationFixtures.settings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.coobi.logistics.eventgenerator.event.VehicleLocationData;
import com.coobi.logistics.eventgenerator.event.VehicleLocationEvent;
import com.coobi.logistics.eventgenerator.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** MVP-1.4: deterministic identities, gradual movement and the 1,000 vehicle target. */
class VehicleTelemetrySimulatorTest {

    private static final Instant START = Instant.parse("2026-09-21T09:00:00Z");
    private static final double KILOMETRES_PER_DEGREE_LATITUDE = 111.32;

    /** One second at the configured maximum speed, plus the rounding tolerance. */
    private static final double MAXIMUM_STEP_KILOMETRES = 110.0 / 3600.0 + 0.0005;
    private static final double MAXIMUM_SPEED_STEP = 5.0 + 0.2;
    private static final double MAXIMUM_HEADING_STEP = 12.0 + 0.2;
    private static final double AREA_RADIUS_KILOMETRES = 30.0;
    private static final double AREA_TOLERANCE_KILOMETRES = 0.01;

    @Test
    void createsDeterministicUniqueVehicleIds() {
        VehicleTelemetrySimulator simulator = simulator(1000, new MutableClock(START));

        List<String> vehicleIds = simulator.vehicleIds();

        assertThat(simulator.vehicleCount()).isEqualTo(1000);
        assertThat(vehicleIds).hasSize(1000).doesNotHaveDuplicates();
        assertThat(vehicleIds.getFirst()).isEqualTo("TRUCK-00001");
        assertThat(vehicleIds.getLast()).isEqualTo("TRUCK-01000");
        assertThat(simulator(1000, new MutableClock(START)).vehicleIds()).isEqualTo(vehicleIds);
    }

    @Test
    void rotatesOverTheWholeFleetWithUniqueEvents() {
        VehicleTelemetrySimulator simulator = simulator(1000, new MutableClock(START));

        List<VehicleLocationEvent> events = simulator.nextEvents(1000);

        assertThat(events).hasSize(1000);
        assertThat(events.stream().map(VehicleLocationEvent::vehicleId))
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(simulator.vehicleIds());
        assertThat(events.stream().map(VehicleLocationEvent::eventId)).doesNotHaveDuplicates();
        assertThat(events).allSatisfy(event -> {
            assertValid(event);
            assertThat(event.timestamp()).isEqualTo(START);
        });
    }

    @Test
    void evolvesEveryVehicleGradually() {
        MutableClock clock = new MutableClock(START);
        VehicleTelemetrySimulator simulator = simulator(1000, clock);
        Map<String, VehicleLocationEvent> previousByVehicle = new HashMap<>();

        for (int cycle = 0; cycle < 25; cycle++) {
            for (VehicleLocationEvent event : simulator.nextEvents(1000)) {
                VehicleLocationEvent previous = previousByVehicle.put(event.vehicleId(), event);
                if (previous != null) {
                    assertGradualStep(previous, event);
                }
            }
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(previousByVehicle).hasSize(1000);
    }

    @Test
    void keepsEveryVehicleInsideTheDemonstrationArea() {
        MutableClock clock = new MutableClock(START);
        VehicleTelemetrySimulator simulator = simulator(1000, clock);
        double maximumDistanceFromCentre = 0.0;

        for (int cycle = 0; cycle < 30; cycle++) {
            for (VehicleLocationEvent event : simulator.nextEvents(1000)) {
                maximumDistanceFromCentre = Math.max(maximumDistanceFromCentre, distanceFromCentre(event.data()));
            }
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(maximumDistanceFromCentre).isLessThanOrEqualTo(AREA_RADIUS_KILOMETRES + AREA_TOLERANCE_KILOMETRES);
    }

    @Test
    void capsTheStepAfterALongPause() {
        MutableClock clock = new MutableClock(START);
        VehicleTelemetrySimulator simulator = simulator(100, clock);
        VehicleLocationEvent before = simulator.nextEvents(100).getFirst();

        // Ten simulated minutes must not move a vehicle further than max-elapsed-seconds allows.
        clock.advance(Duration.ofMinutes(10));
        VehicleLocationEvent after = simulator.nextEvents(100).getFirst();

        assertThat(distance(before.data(), after.data())).isLessThanOrEqualTo(110.0 * 10.0 / 3600.0 + 0.0005);
    }

    @Test
    void replaysTheSameTrajectoryForTheSameSeed() {
        MutableClock firstClock = new MutableClock(START);
        MutableClock secondClock = new MutableClock(START);
        VehicleTelemetrySimulator first = simulator(500, firstClock);
        VehicleTelemetrySimulator second = simulator(500, secondClock);
        List<String> firstTrajectory = new ArrayList<>();
        List<String> secondTrajectory = new ArrayList<>();

        for (int cycle = 0; cycle < 10; cycle++) {
            first.nextEvents(500).forEach(event -> firstTrajectory.add(telemetryOf(event)));
            second.nextEvents(500).forEach(event -> secondTrajectory.add(telemetryOf(event)));
            firstClock.advance(Duration.ofSeconds(1));
            secondClock.advance(Duration.ofSeconds(1));
        }

        // The eventId is unique per event by contract, so determinism is asserted on the
        // telemetry itself: same vehicle, same instant, same position, speed and heading.
        assertThat(firstTrajectory).hasSize(5000).isEqualTo(secondTrajectory);
    }

    private static String telemetryOf(VehicleLocationEvent event) {
        return event.vehicleId() + "|" + event.timestamp() + "|" + event.data();
    }

    @Test
    void supportsMoreEventsThanVehicles() {
        VehicleTelemetrySimulator simulator = simulator(1000, new MutableClock(START));

        List<VehicleLocationEvent> events = simulator.nextEvents(5000);

        assertThat(events).hasSize(5000);
        assertThat(events).allSatisfy(VehicleTelemetrySimulatorTest::assertValid);
        // A batch larger than the fleet revisits vehicles, still in round-robin order.
        assertThat(events.get(1000).vehicleId()).isEqualTo("TRUCK-00001");
        assertThat(events.get(4999).vehicleId()).isEqualTo("TRUCK-01000");
    }

    @Test
    void rejectsANegativeBatchSize() {
        VehicleTelemetrySimulator simulator = simulator(10, new MutableClock(START));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> simulator.nextEvents(-1))
                .withMessageContaining("count");
    }

    private static VehicleTelemetrySimulator simulator(int vehicleCount, MutableClock clock) {
        return new VehicleTelemetrySimulator(settings("TRUCK-", vehicleCount, 0.0, 110.0), clock);
    }

    private static void assertGradualStep(VehicleLocationEvent previous, VehicleLocationEvent current) {
        assertValid(current);
        assertThat(current.vehicleId()).isEqualTo(previous.vehicleId());
        assertThat(Duration.between(previous.timestamp(), current.timestamp())).isEqualTo(Duration.ofSeconds(1));

        double step = distance(previous.data(), current.data());
        assertThat(step)
                .as("vehicle %s must not jump between unrelated locations", current.vehicleId())
                .isLessThanOrEqualTo(MAXIMUM_STEP_KILOMETRES)
                .isGreaterThanOrEqualTo(0.0);
        assertThat(current.data().speed() - previous.data().speed())
                .as("speed must evolve gradually")
                .isBetween(-MAXIMUM_SPEED_STEP, MAXIMUM_SPEED_STEP);
        assertThat(headingDifference(previous.data().heading(), current.data().heading()))
                .as("heading must evolve gradually")
                .isLessThanOrEqualTo(MAXIMUM_HEADING_STEP);
    }

    private static void assertValid(VehicleLocationEvent event) {
        VehicleLocationData data = event.data();
        assertThat(data.latitude()).isBetween(-90.0, 90.0);
        assertThat(data.longitude()).isBetween(-180.0, 180.0);
        assertThat(data.speed()).isGreaterThanOrEqualTo(0.0).isLessThanOrEqualTo(110.0);
        assertThat(data.heading()).isGreaterThanOrEqualTo(0.0).isLessThan(360.0);
        assertThat(event.timestamp()).isNotNull();
    }

    private static double distance(VehicleLocationData from, VehicleLocationData to) {
        double northKilometres = (to.latitude() - from.latitude()) * KILOMETRES_PER_DEGREE_LATITUDE;
        double eastKilometres = (to.longitude() - from.longitude())
                * KILOMETRES_PER_DEGREE_LATITUDE
                * Math.cos(Math.toRadians(from.latitude()));
        return Math.hypot(northKilometres, eastKilometres);
    }

    private static double distanceFromCentre(VehicleLocationData data) {
        double northKilometres = (data.latitude() - 39.4699) * KILOMETRES_PER_DEGREE_LATITUDE;
        double eastKilometres = (data.longitude() + 0.3763)
                * KILOMETRES_PER_DEGREE_LATITUDE
                * Math.cos(Math.toRadians(39.4699));
        return Math.hypot(northKilometres, eastKilometres);
    }

    private static double headingDifference(double from, double to) {
        double difference = Math.abs(((to - from) % 360.0) + 360.0) % 360.0;
        return Math.min(difference, 360.0 - difference);
    }
}

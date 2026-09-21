package com.coobi.logistics.eventgenerator.simulation;

import com.coobi.logistics.eventgenerator.event.VehicleLocationData;
import com.coobi.logistics.eventgenerator.event.VehicleLocationEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

/**
 * Deterministic vehicle fleet simulator.
 *
 * <p>Every vehicle keeps its own position, speed and heading. An update advances that
 * state by the time elapsed since the previous update of the <em>same</em> vehicle, so
 * distance, speed and timestamp stay consistent: a vehicle never jumps to an unrelated
 * location, and it is steered back towards the centre when it reaches the edge of the
 * demonstration area.
 *
 * <p>Determinism: identifiers, the initial fleet distribution and every random step come
 * from a single {@link Random} seeded with {@link VehicleSimulationSettings#randomSeed()}
 * and consumed in round-robin order. Two simulators with the same settings, driven with
 * the same clock instants, replay the same trajectory.
 *
 * <p>Not thread-safe by design: the publishing scheduler drives it from one thread.
 */
public final class VehicleTelemetrySimulator {

    /** Degrees of latitude per kilometre, accurate enough for a local demonstration area. */
    private static final double KILOMETRES_PER_DEGREE_LATITUDE = 111.32;

    private static final double MINIMUM_LATITUDE = -90.0;
    private static final double MAXIMUM_LATITUDE = 90.0;
    private static final double MINIMUM_LONGITUDE = -180.0;
    private static final double MAXIMUM_LONGITUDE = 180.0;

    private static final int COORDINATE_SCALE_DECIMALS = 6;
    private static final double COORDINATE_SCALE = 1_000_000.0;
    private static final double HEADING_SCALE = 10.0;
    private static final double SPEED_SCALE = 10.0;

    private final VehicleSimulationSettings settings;
    private final Clock clock;
    private final Random random;
    private final List<VehicleState> vehicles;

    private int nextVehicleIndex;

    public VehicleTelemetrySimulator(VehicleSimulationSettings settings, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.random = new Random(settings.randomSeed());
        this.vehicles = initialFleet();
    }

    /** Number of simulated vehicles. */
    public int vehicleCount() {
        return vehicles.size();
    }

    /** Vehicle identifiers in round-robin order. */
    public List<String> vehicleIds() {
        return vehicles.stream().map(VehicleState::vehicleId).toList();
    }

    /** Advances the round-robin cursor once and returns the next event. */
    public VehicleLocationEvent nextEvent() {
        VehicleState state = vehicles.get(nextVehicleIndex);
        nextVehicleIndex = (nextVehicleIndex + 1) % vehicles.size();

        Instant timestamp = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        advance(state, timestamp);

        VehicleLocationData data = new VehicleLocationData(
                round(state.latitude(), COORDINATE_SCALE),
                round(state.longitude(), COORDINATE_SCALE),
                round(state.speedKph(), SPEED_SCALE),
                roundHeading(state.headingDegrees()));
        return VehicleLocationEvent.create(state.vehicleId(), timestamp, data);
    }

    /**
     * Returns {@code count} consecutive events, each one advancing a different vehicle.
     *
     * <p>The batch size is bounded by the caller, which keeps the publication rate
     * independent of the fleet size and avoids one task per vehicle.
     */
    public List<VehicleLocationEvent> nextEvents(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative but was " + count);
        }
        List<VehicleLocationEvent> events = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            events.add(nextEvent());
        }
        return List.copyOf(events);
    }

    private List<VehicleState> initialFleet() {
        List<VehicleState> fleet = new ArrayList<>(settings.vehicleCount());
        Instant now = clock.instant();
        double speedRange = settings.maxSpeedKph() - settings.minSpeedKph();
        for (int index = 1; index <= settings.vehicleCount(); index++) {
            // Uniform disc sampling: sqrt keeps the density constant across the area.
            double distanceKilometres = settings.areaRadiusKilometres() * Math.sqrt(random.nextDouble());
            double bearingDegrees = random.nextDouble() * 360.0;
            double latitude = settings.centerLatitude() + northwardsDegrees(distanceKilometres, bearingDegrees);
            double longitude = settings.centerLongitude() + eastwardsDegrees(distanceKilometres, bearingDegrees, settings.centerLatitude());
            double speedKph = settings.minSpeedKph() + (random.nextDouble() * speedRange);
            double headingDegrees = random.nextDouble() * 360.0;
            fleet.add(new VehicleState(
                    vehicleId(index), latitude, longitude, speedKph, headingDegrees, now));
        }
        return fleet;
    }

    private String vehicleId(int index) {
        return settings.vehicleIdPrefix() + String.format(Locale.ROOT, "%05d", index);
    }

    private void advance(VehicleState state, Instant timestamp) {
        double elapsedSeconds = Math.min(
                Math.max(Duration.between(state.lastUpdateAt(), timestamp).toNanos() / 1_000_000_000.0, 0.0),
                settings.maxElapsedSeconds());

        double speedDelta = nextSignedDelta(settings.maxSpeedDeltaKph());
        double speedKph = clamp(state.speedKph() + speedDelta, settings.minSpeedKph(), settings.maxSpeedKph());

        double headingDelta = nextSignedDelta(settings.maxHeadingDeltaDegrees());
        double headingDegrees = normalizeHeading(state.headingDegrees() + headingDelta);

        double travelledKilometres = speedKph * elapsedSeconds / 3600.0;
        double latitude = state.latitude() + northwardsDegrees(travelledKilometres, headingDegrees);
        double longitude = state.longitude() + eastwardsDegrees(travelledKilometres, headingDegrees, state.latitude());

        double northOffsetKilometres = (latitude - settings.centerLatitude()) * KILOMETRES_PER_DEGREE_LATITUDE;
        double eastOffsetKilometres = (longitude - settings.centerLongitude())
                * KILOMETRES_PER_DEGREE_LATITUDE
                * Math.cos(Math.toRadians(settings.centerLatitude()));
        double distanceFromCentreKilometres = Math.hypot(northOffsetKilometres, eastOffsetKilometres);

        if (distanceFromCentreKilometres > settings.areaRadiusKilometres()) {
            // Clamp onto the boundary, then turn back towards the centre with the
            // heading budget that the random turn did not use: the area is respected
            // and the heading still evolves gradually.
            double scale = settings.areaRadiusKilometres() / distanceFromCentreKilometres;
            latitude = settings.centerLatitude() + ((latitude - settings.centerLatitude()) * scale);
            longitude = settings.centerLongitude() + ((longitude - settings.centerLongitude()) * scale);
            double inwardHeading = normalizeHeading(
                    Math.toDegrees(Math.atan2(-eastOffsetKilometres, -northOffsetKilometres)));
            double steeringBudget = Math.max(0.0, settings.maxHeadingDeltaDegrees() - Math.abs(headingDelta));
            double steering = clamp(shortestHeadingDelta(headingDegrees, inwardHeading), -steeringBudget, steeringBudget);
            headingDegrees = normalizeHeading(headingDegrees + steering);
        }

        state.apply(
                clamp(latitude, MINIMUM_LATITUDE, MAXIMUM_LATITUDE),
                clamp(longitude, MINIMUM_LONGITUDE, MAXIMUM_LONGITUDE),
                speedKph,
                headingDegrees,
                timestamp);
    }

    private static double northwardsDegrees(double kilometres, double headingDegrees) {
        return (kilometres * Math.cos(Math.toRadians(headingDegrees))) / KILOMETRES_PER_DEGREE_LATITUDE;
    }

    private static double eastwardsDegrees(double kilometres, double headingDegrees, double latitude) {
        return (kilometres * Math.sin(Math.toRadians(headingDegrees)))
                / (KILOMETRES_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(latitude)));
    }

    private double nextSignedDelta(double maximumDelta) {
        return ((random.nextDouble() * 2.0) - 1.0) * maximumDelta;
    }

    private static double normalizeHeading(double headingDegrees) {
        double normalized = headingDegrees % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    /** Shortest signed arc from {@code from} to {@code to}, within -180..180. */
    private static double shortestHeadingDelta(double from, double to) {
        double delta = normalizeHeading(to) - normalizeHeading(from);
        if (delta > 180.0) {
            delta -= 360.0;
        } else if (delta < -180.0) {
            delta += 360.0;
        }
        return delta;
    }

    private static double roundHeading(double headingDegrees) {
        double rounded = round(headingDegrees, HEADING_SCALE);
        // Rounding 359.97 must not produce the invalid value 360.0.
        return rounded >= 360.0 ? 0.0 : rounded;
    }

    private static double round(double value, double scale) {
        return Math.round(value * scale) / scale;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.min(Math.max(value, minimum), maximum);
    }
}

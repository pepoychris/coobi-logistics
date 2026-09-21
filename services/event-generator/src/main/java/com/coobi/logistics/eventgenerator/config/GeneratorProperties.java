package com.coobi.logistics.eventgenerator.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Generator configuration bound from {@code coobi.generator.*}.
 *
 * <p>The mode decides which pair of counts is effective: {@link GeneratorMode#NORMAL}
 * uses {@code vehicle-count} and {@code target-events-per-second}, while
 * {@link GeneratorMode#LOAD_TEST} uses the {@code load-test.*} values.
 *
 * <p>Bean Validation runs on startup, so an out-of-range value stops the service
 * instead of producing a silently wrong fleet (Global Rule 11).
 */
@ConfigurationProperties(prefix = "coobi.generator")
@Validated
public class GeneratorProperties {

    private static final int MAX_VEHICLES = 1_000_000;
    private static final int MAX_EVENTS_PER_SECOND = 2_000_000;

    @NotNull
    private GeneratorMode mode = GeneratorMode.NORMAL;

    @Min(1)
    @Max(MAX_VEHICLES)
    private int vehicleCount = 1000;

    @Min(1)
    @Max(MAX_EVENTS_PER_SECOND)
    private int targetEventsPerSecond = 1000;

    private boolean publishEnabled = true;

    /** Length of one publishing tick; the target rate is spread across ticks. */
    @Min(10)
    @Max(60_000)
    private int tickIntervalMillis = 100;

    @Min(1_000)
    @Max(3_600_000)
    private int throughputLogIntervalMillis = 30_000;

    @NotNull
    @Valid
    private LoadTest loadTest = new LoadTest();

    @NotNull
    @Valid
    private Simulation simulation = new Simulation();

    public GeneratorMode getMode() {
        return mode;
    }

    public void setMode(GeneratorMode mode) {
        this.mode = mode;
    }

    public int getVehicleCount() {
        return vehicleCount;
    }

    public void setVehicleCount(int vehicleCount) {
        this.vehicleCount = vehicleCount;
    }

    public int getTargetEventsPerSecond() {
        return targetEventsPerSecond;
    }

    public void setTargetEventsPerSecond(int targetEventsPerSecond) {
        this.targetEventsPerSecond = targetEventsPerSecond;
    }

    public boolean isPublishEnabled() {
        return publishEnabled;
    }

    public void setPublishEnabled(boolean publishEnabled) {
        this.publishEnabled = publishEnabled;
    }

    public int getTickIntervalMillis() {
        return tickIntervalMillis;
    }

    public void setTickIntervalMillis(int tickIntervalMillis) {
        this.tickIntervalMillis = tickIntervalMillis;
    }

    public int getThroughputLogIntervalMillis() {
        return throughputLogIntervalMillis;
    }

    public void setThroughputLogIntervalMillis(int throughputLogIntervalMillis) {
        this.throughputLogIntervalMillis = throughputLogIntervalMillis;
    }

    public LoadTest getLoadTest() {
        return loadTest;
    }

    public void setLoadTest(LoadTest loadTest) {
        this.loadTest = loadTest;
    }

    public Simulation getSimulation() {
        return simulation;
    }

    public void setSimulation(Simulation simulation) {
        this.simulation = simulation;
    }

    /** Simulated vehicles for the active mode. */
    public int effectiveVehicleCount() {
        return mode == GeneratorMode.LOAD_TEST ? loadTest.getVehicleCount() : vehicleCount;
    }

    /** Publication rate for the active mode, in events per second. */
    public int effectiveTargetEventsPerSecond() {
        return mode == GeneratorMode.LOAD_TEST ? loadTest.getTargetEventsPerSecond() : targetEventsPerSecond;
    }

    /**
     * How long the active mode publishes before the generator stops on its own:
     * {@link Duration#ZERO} means no limit.
     *
     * <p>The bound belongs to the load-test profile, so a demonstration run cannot be cut
     * short by it: a bounded run is a property of a benchmark, not of the simulator.
     */
    public Duration effectivePublishingDuration() {
        if (mode != GeneratorMode.LOAD_TEST || loadTest.getDuration() == null) {
            return Duration.ZERO;
        }
        return loadTest.getDuration();
    }

    /** Load-test overrides. */
    public static class LoadTest {

        @Min(1)
        @Max(MAX_VEHICLES)
        private int vehicleCount = 5000;

        @Min(1)
        @Max(MAX_EVENTS_PER_SECOND)
        private int targetEventsPerSecond = 20_000;

        /**
         * Publishing window of a load test; zero, the default, publishes until the service is
         * stopped. A negative value is rejected at startup instead of being read as a window
         * that already elapsed (Global Rule 11).
         */
        @NotNull
        private Duration duration = Duration.ZERO;

        public int getVehicleCount() {
            return vehicleCount;
        }

        public void setVehicleCount(int vehicleCount) {
            this.vehicleCount = vehicleCount;
        }

        public int getTargetEventsPerSecond() {
            return targetEventsPerSecond;
        }

        public void setTargetEventsPerSecond(int targetEventsPerSecond) {
            this.targetEventsPerSecond = targetEventsPerSecond;
        }

        public Duration getDuration() {
            return duration;
        }

        public void setDuration(Duration duration) {
            this.duration = duration;
        }

        /**
         * The duration is validated as a {@link Duration} rather than as a number, because a
         * {@code @Min} constraint has no validator for it: this is the constraint that makes a
         * negative window fail the startup instead of publishing nothing without saying so.
         */
        @AssertTrue(message = "coobi.generator.load-test.duration must be zero or positive")
        public boolean isDurationNotNegative() {
            return duration == null || !duration.isNegative();
        }
    }

    /** Movement model of the simulated fleet. */
    public static class Simulation {

        private long randomSeed = 20_260_101L;

        @DecimalMin("-85.0")
        @DecimalMax("85.0")
        private double centerLatitude = 39.4699;

        @DecimalMin("-180.0")
        @DecimalMax("180.0")
        private double centerLongitude = -0.3763;

        @DecimalMin("0.1")
        @DecimalMax("500.0")
        private double areaRadiusKilometres = 30.0;

        @DecimalMin("0.0")
        @DecimalMax("400.0")
        private double minSpeedKph = 0.0;

        @DecimalMin("0.0")
        @DecimalMax("400.0")
        private double maxSpeedKph = 110.0;

        @DecimalMin("0.0")
        @DecimalMax("100.0")
        private double maxSpeedDeltaKph = 5.0;

        @DecimalMin("0.0")
        @DecimalMax("180.0")
        private double maxHeadingDeltaDegrees = 12.0;

        @DecimalMin("0.1")
        @DecimalMax("600.0")
        private double maxElapsedSeconds = 10.0;

        @NotBlank
        @Size(max = 32)
        private String vehicleIdPrefix = "TRUCK-";

        public long getRandomSeed() {
            return randomSeed;
        }

        public void setRandomSeed(long randomSeed) {
            this.randomSeed = randomSeed;
        }

        public double getCenterLatitude() {
            return centerLatitude;
        }

        public void setCenterLatitude(double centerLatitude) {
            this.centerLatitude = centerLatitude;
        }

        public double getCenterLongitude() {
            return centerLongitude;
        }

        public void setCenterLongitude(double centerLongitude) {
            this.centerLongitude = centerLongitude;
        }

        public double getAreaRadiusKilometres() {
            return areaRadiusKilometres;
        }

        public void setAreaRadiusKilometres(double areaRadiusKilometres) {
            this.areaRadiusKilometres = areaRadiusKilometres;
        }

        public double getMinSpeedKph() {
            return minSpeedKph;
        }

        public void setMinSpeedKph(double minSpeedKph) {
            this.minSpeedKph = minSpeedKph;
        }

        public double getMaxSpeedKph() {
            return maxSpeedKph;
        }

        public void setMaxSpeedKph(double maxSpeedKph) {
            this.maxSpeedKph = maxSpeedKph;
        }

        public double getMaxSpeedDeltaKph() {
            return maxSpeedDeltaKph;
        }

        public void setMaxSpeedDeltaKph(double maxSpeedDeltaKph) {
            this.maxSpeedDeltaKph = maxSpeedDeltaKph;
        }

        public double getMaxHeadingDeltaDegrees() {
            return maxHeadingDeltaDegrees;
        }

        public void setMaxHeadingDeltaDegrees(double maxHeadingDeltaDegrees) {
            this.maxHeadingDeltaDegrees = maxHeadingDeltaDegrees;
        }

        public double getMaxElapsedSeconds() {
            return maxElapsedSeconds;
        }

        public void setMaxElapsedSeconds(double maxElapsedSeconds) {
            this.maxElapsedSeconds = maxElapsedSeconds;
        }

        public String getVehicleIdPrefix() {
            return vehicleIdPrefix;
        }

        public void setVehicleIdPrefix(String vehicleIdPrefix) {
            this.vehicleIdPrefix = vehicleIdPrefix;
        }
    }
}

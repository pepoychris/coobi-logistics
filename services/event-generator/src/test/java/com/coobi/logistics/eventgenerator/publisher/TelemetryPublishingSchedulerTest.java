package com.coobi.logistics.eventgenerator.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.coobi.logistics.eventgenerator.config.GeneratorMode;
import com.coobi.logistics.eventgenerator.config.GeneratorProperties;
import com.coobi.logistics.eventgenerator.config.KafkaTopicsProperties;
import com.coobi.logistics.eventgenerator.event.VehicleLocationEvent;
import com.coobi.logistics.eventgenerator.simulation.VehicleTelemetrySimulator;
import com.coobi.logistics.eventgenerator.support.SimulationFixtures;
import com.coobi.logistics.eventgenerator.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/** MVP-1.5: batch sizing, rate targets and the scheduled lifecycle. */
class TelemetryPublishingSchedulerTest {

    private GeneratorProperties properties;
    private RecordingPublisher publisher;
    private VehicleTelemetrySimulator simulator;
    private TaskScheduler taskScheduler;

    @BeforeEach
    void setUp() {
        properties = new GeneratorProperties();
        publisher = new RecordingPublisher();
        simulator = new VehicleTelemetrySimulator(
                SimulationFixtures.normalModeFleet(),
                new MutableClock(Instant.parse("2026-09-21T09:00:00Z")));
        taskScheduler = mock(TaskScheduler.class);
    }

    @Test
    void publishesTheRatePlannedBatchOnEveryTick() {
        TelemetryPublishingScheduler scheduler = newScheduler();

        scheduler.publishNextBatch();
        assertThat(publisher.published).hasSize(100);

        scheduler.publishNextBatch();
        assertThat(publisher.published).hasSize(200);
        assertThat(publisher.published.get(100).vehicleId()).isEqualTo("TRUCK-00101");
        assertThat(publisher.published.stream().map(VehicleLocationEvent::eventId)).doesNotHaveDuplicates();
    }

    @Test
    void publishesNothingWhenPublishingIsDisabled() {
        properties.setPublishEnabled(false);
        TelemetryPublishingScheduler scheduler = newScheduler();

        scheduler.publishNextBatch();

        assertThat(publisher.published).isEmpty();
    }

    @Test
    void honoursTheLoadTestRate() {
        properties.setMode(GeneratorMode.LOAD_TEST);
        properties.getLoadTest().setVehicleCount(5000);
        properties.getLoadTest().setTargetEventsPerSecond(20_000);
        TelemetryPublishingScheduler scheduler = newScheduler();

        scheduler.publishNextBatch();

        assertThat(publisher.published).hasSize(2000);
    }

    @Test
    void schedulesAndCancelsThePublishingTask() {
        ScheduledFuture<?> scheduledTask = mock(ScheduledFuture.class);
        doReturn(scheduledTask)
                .when(taskScheduler)
                .scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofMillis(100)));
        TelemetryPublishingScheduler scheduler = newScheduler();

        scheduler.start();

        assertThat(scheduler.isRunning()).isTrue();
        verify(taskScheduler).scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofMillis(100)));

        scheduler.stop();

        assertThat(scheduler.isRunning()).isFalse();
        verify(scheduledTask).cancel(false);
    }

    @Test
    void doesNotScheduleWhenPublishingIsDisabled() {
        properties.setPublishEnabled(false);
        TelemetryPublishingScheduler scheduler = newScheduler();

        scheduler.start();

        assertThat(scheduler.isRunning()).isTrue();
        verifyNoInteractions(taskScheduler);
    }

    @Test
    void keepsRunningWhenATickFails() {
        publisher.failNext = true;
        TelemetryPublishingScheduler scheduler = newScheduler();

        scheduler.publishNextBatch();

        assertThat(publisher.failures).isPositive();
    }

    private TelemetryPublishingScheduler newScheduler() {
        return new TelemetryPublishingScheduler(
                properties, new KafkaTopicsProperties(), simulator, publisher, taskScheduler);
    }

    /** Records what a tick would have published. */
    private static final class RecordingPublisher implements TelemetryPublisher {

        private final List<VehicleLocationEvent> published = new ArrayList<>();
        private long failures;
        private boolean failNext;

        @Override
        public void publish(VehicleLocationEvent event) {
            if (failNext) {
                failures++;
                throw new IllegalStateException("simulated broker outage for " + event.vehicleId());
            }
            published.add(event);
        }

        @Override
        public long publishedCount() {
            return published.size();
        }

        @Override
        public long failedCount() {
            return failures;
        }
    }
}

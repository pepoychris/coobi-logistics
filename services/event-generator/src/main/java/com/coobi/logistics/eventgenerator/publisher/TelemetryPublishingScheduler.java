package com.coobi.logistics.eventgenerator.publisher;

import com.coobi.logistics.eventgenerator.config.GeneratorProperties;
import com.coobi.logistics.eventgenerator.config.KafkaTopicsProperties;
import com.coobi.logistics.eventgenerator.event.VehicleLocationEvent;
import com.coobi.logistics.eventgenerator.kafka.KafkaTopicInitializer;
import com.coobi.logistics.eventgenerator.simulation.VehicleTelemetrySimulator;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Drives the generator at the configured target rate.
 *
 * <p>One scheduled task, and one batch per tick: the fleet never gets a thread per
 * vehicle, and {@link PublishRatePlanner} keeps the average rate close to the target
 * while bounding the size of a single batch.
 *
 * <p>Implements {@link SmartLifecycle} so the publishing task is cancelled during a
 * graceful shutdown, after which the publisher drains the producer.
 */
@Component
public class TelemetryPublishingScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelemetryPublishingScheduler.class);

    /**
     * Allows a tick to catch up by a few nominal batches, and no more: a stalled
     * scheduler must not turn into an unbounded burst.
     */
    private static final int MAX_BURST_FACTOR = 5;

    /**
     * Starts after {@link KafkaTopicInitializer#PHASE}, so the first event is published
     * only once the topics exist. Provisioning runs while the context is starting, and a
     * failed provisioning aborts the startup before this phase is reached.
     */
    public static final int PHASE = KafkaTopicInitializer.PHASE + 100;

    private final GeneratorProperties generatorProperties;
    private final KafkaTopicsProperties kafkaTopicsProperties;
    private final VehicleTelemetrySimulator simulator;
    private final TelemetryPublisher publisher;
    private final TaskScheduler taskScheduler;
    private final PublishRatePlanner ratePlanner = new PublishRatePlanner();
    private final AtomicBoolean tickInProgress = new AtomicBoolean();

    private volatile ScheduledFuture<?> publishingTask;
    private volatile boolean running;
    private long lastThroughputReportNanos;
    private long lastReportedPublishedCount;

    public TelemetryPublishingScheduler(
            GeneratorProperties generatorProperties,
            KafkaTopicsProperties kafkaTopicsProperties,
            VehicleTelemetrySimulator simulator,
            TelemetryPublisher publisher,
            TaskScheduler taskScheduler) {
        this.generatorProperties = generatorProperties;
        this.kafkaTopicsProperties = kafkaTopicsProperties;
        this.simulator = simulator;
        this.publisher = publisher;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        if (!generatorProperties.isPublishEnabled()) {
            log.warn("telemetry publishing disabled, the generator will not publish events");
            return;
        }
        Duration tickInterval = Duration.ofMillis(generatorProperties.getTickIntervalMillis());
        publishingTask = taskScheduler.scheduleAtFixedRate(this::publishNextBatch, tickInterval);
        lastThroughputReportNanos = System.nanoTime();
        lastReportedPublishedCount = publisher.publishedCount();
        log.info(
                "telemetry publishing started mode={} vehicles={} target-events-per-second={} tick-interval-millis={} topic={}",
                generatorProperties.getMode(),
                simulator.vehicleCount(),
                generatorProperties.effectiveTargetEventsPerSecond(),
                generatorProperties.getTickIntervalMillis(),
                kafkaTopicsProperties.locationTopic());
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        ScheduledFuture<?> task = publishingTask;
        publishingTask = null;
        if (task != null) {
            task.cancel(false);
            log.info(
                    "telemetry publishing stopped published-total={} failed-total={}",
                    publisher.publishedCount(),
                    publisher.failedCount());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Generates and publishes one bounded batch, then reports throughput when the
     * configured reporting interval has elapsed.
     */
    void publishNextBatch() {
        if (!generatorProperties.isPublishEnabled()) {
            return;
        }
        if (!tickInProgress.compareAndSet(false, true)) {
            log.warn("telemetry tick skipped, the previous tick is still running");
            return;
        }
        try {
            int tickIntervalMillis = generatorProperties.getTickIntervalMillis();
            int targetEventsPerSecond = generatorProperties.effectiveTargetEventsPerSecond();
            int nominalBatchSize = Math.max(
                    1, (int) Math.ceil(((double) targetEventsPerSecond) * tickIntervalMillis / 1000.0));
            int batchSize = ratePlanner.planBatch(
                    targetEventsPerSecond, tickIntervalMillis, nominalBatchSize * MAX_BURST_FACTOR);
            if (batchSize <= 0) {
                return;
            }
            List<VehicleLocationEvent> events = simulator.nextEvents(batchSize);
            for (VehicleLocationEvent event : events) {
                publisher.publish(event);
            }
            reportThroughputIfDue();
        } catch (RuntimeException tickFailure) {
            // A scheduled task stops after an uncaught exception, so a failing tick is
            // reported and the next tick continues.
            log.error(
                    "telemetry tick failed vehicle-count={} reason={}",
                    simulator.vehicleCount(),
                    tickFailure.getMessage(),
                    tickFailure);
        } finally {
            tickInProgress.set(false);
        }
    }

    private void reportThroughputIfDue() {
        long nowNanos = System.nanoTime();
        long elapsedNanos = nowNanos - lastThroughputReportNanos;
        if (elapsedNanos < Duration.ofMillis(generatorProperties.getThroughputLogIntervalMillis()).toNanos()) {
            return;
        }
        long published = publisher.publishedCount();
        long publishedInWindow = published - lastReportedPublishedCount;
        double eventsPerSecond = publishedInWindow * 1_000_000_000.0 / elapsedNanos;
        log.info(
                "telemetry throughput events-per-second={} published-total={} failed-total={} window-millis={}",
                String.format(Locale.ROOT, "%.1f", eventsPerSecond),
                published,
                publisher.failedCount(),
                elapsedNanos / 1_000_000);
        lastThroughputReportNanos = nowNanos;
        lastReportedPublishedCount = published;
    }
}

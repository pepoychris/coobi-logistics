package com.coobi.logistics.eventgenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coobi.logistics.eventgenerator.config.GeneratorProperties;
import com.coobi.logistics.eventgenerator.config.KafkaTopicsProperties;
import com.coobi.logistics.eventgenerator.event.VehicleLocationEvent;
import com.coobi.logistics.eventgenerator.kafka.KafkaTopicInitializer;
import com.coobi.logistics.eventgenerator.publisher.TelemetryPublisher;
import com.coobi.logistics.eventgenerator.publisher.TelemetryPublishingScheduler;
import com.coobi.logistics.eventgenerator.simulation.VehicleTelemetrySimulator;
import com.coobi.logistics.eventgenerator.support.MutableClock;
import com.coobi.logistics.eventgenerator.support.SimulationFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.TaskScheduler;

/**
 * MVP-1.3/MVP-1.5: the topics are in place before the first event is published.
 *
 * <p>Topic provisioning and publishing are both
 * {@link org.springframework.context.SmartLifecycle} beans, and a context starts such
 * beans in phase order: the initializer runs to completion before the scheduler is
 * started, and a failed provisioning aborts the refresh instead of leaving a publisher
 * running with no destination.
 *
 * <p>The test drives a real context with a mocked Admin client, so no broker is needed,
 * and the stubbed task scheduler runs the publishing task as soon as the scheduler
 * registers it. If the scheduler ever started first, it would publish before a single
 * topic had been checked.
 */
class PublishingLifecycleOrderTests {

    private static final String LOCATION_TOPIC = "logistics.vehicle.location.v1";
    private static final String DEAD_LETTER_TOPIC = "logistics.vehicle.location.dlq.v1";
    private static final String PROVISIONING = "provisioning";
    private static final String PUBLISH = "publish";
    private static final Instant START = Instant.parse("2026-09-21T09:00:00Z");

    private final List<String> journal = new ArrayList<>();

    @Test
    void publishesTheFirstEventOnlyAfterTheTopicsHaveBeenProvisioned() {
        KafkaTopicsProperties topicsProperties = new KafkaTopicsProperties();
        KafkaTopicInitializer initializer = new KafkaTopicInitializer(adminWithExistingTopics(), topicsProperties);
        RecordingPublisher publisher = new RecordingPublisher();
        TelemetryPublishingScheduler scheduler = new TelemetryPublishingScheduler(
                generatorProperties(), topicsProperties, simulator(), publisher, taskSchedulerRunningOneTick());

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("kafkaTopicInitializer", KafkaTopicInitializer.class, () -> initializer);
            context.registerBean("telemetryPublishingScheduler", TelemetryPublishingScheduler.class, () -> scheduler);
            context.refresh();

            assertThat(initializer.isRunning()).isTrue();
            assertThat(scheduler.isRunning()).isTrue();
            assertThat(publisher.publishedCount()).isPositive();

            int firstPublish = journal.indexOf(PUBLISH);
            assertThat(firstPublish).isGreaterThan(0);
            assertThat(journal.subList(0, firstPublish)).containsOnly(PROVISIONING);

            context.close();

            assertThat(scheduler.isRunning()).isFalse();
            assertThat(initializer.isRunning()).isFalse();
        }
    }

    /** 10 vehicles at 100 events/s and the 100 ms default tick: 10 events per tick. */
    private static GeneratorProperties generatorProperties() {
        GeneratorProperties properties = new GeneratorProperties();
        properties.setVehicleCount(10);
        properties.setTargetEventsPerSecond(100);
        return properties;
    }

    private static VehicleTelemetrySimulator simulator() {
        return new VehicleTelemetrySimulator(
                SimulationFixtures.settings("TRUCK-", 10, 0.0, 110.0), new MutableClock(START));
    }

    /** An Admin client for a broker that already holds both topics with six partitions. */
    private Admin adminWithExistingTopics() {
        Admin admin = mock(Admin.class);

        // Every future is built before the stubbing that consumes it: Mockito rejects a
        // nested `when(...)` while another stubbing is still unfinished.
        KafkaFuture<Set<String>> existingNames = completedFuture(Set.of(LOCATION_TOPIC, DEAD_LETTER_TOPIC));
        ListTopicsResult listTopics = mock(ListTopicsResult.class);
        when(listTopics.names()).thenReturn(existingNames);
        when(admin.listTopics()).thenAnswer(invocation -> {
            journal.add(PROVISIONING);
            return listTopics;
        });

        Map<String, TopicDescription> descriptions = new LinkedHashMap<>();
        descriptions.put(LOCATION_TOPIC, topicWithSixPartitions());
        descriptions.put(DEAD_LETTER_TOPIC, topicWithSixPartitions());
        KafkaFuture<Map<String, TopicDescription>> describedTopics = completedFuture(descriptions);
        DescribeTopicsResult describeTopics = mock(DescribeTopicsResult.class);
        when(describeTopics.allTopicNames()).thenReturn(describedTopics);
        when(admin.describeTopics(anyCollection())).thenAnswer(invocation -> {
            journal.add(PROVISIONING);
            return describeTopics;
        });

        return admin;
    }

    /**
     * Registers the publishing task and runs one tick immediately, so the ordering of
     * the first publish is observable without waiting for a scheduler thread.
     */
    @SuppressWarnings("unchecked")
    private TaskScheduler taskSchedulerRunningOneTick() {
        ScheduledFuture<Object> registeredTask = mock(ScheduledFuture.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Duration.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return registeredTask;
        });
        return taskScheduler;
    }

    private static TopicDescription topicWithSixPartitions() {
        List<TopicPartitionInfo> partitions = Collections.nCopies(6, null);
        TopicDescription description = mock(TopicDescription.class);
        when(description.partitions()).thenReturn(partitions);
        return description;
    }

    @SuppressWarnings("unchecked")
    private static <T> KafkaFuture<T> completedFuture(T value) {
        KafkaFuture<T> future = mock(KafkaFuture.class);
        try {
            when(future.get(anyLong(), any())).thenReturn(value);
        } catch (Exception failure) {
            throw new IllegalStateException("stubbing a completed kafka future failed", failure);
        }
        return future;
    }

    /** Records every publish in the shared journal. */
    private final class RecordingPublisher implements TelemetryPublisher {

        private long published;

        @Override
        public void publish(VehicleLocationEvent event) {
            journal.add(PUBLISH);
            published++;
        }

        @Override
        public long publishedCount() {
            return published;
        }

        @Override
        public long failedCount() {
            return 0;
        }
    }
}

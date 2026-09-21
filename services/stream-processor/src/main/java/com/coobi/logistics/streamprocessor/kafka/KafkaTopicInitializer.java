package com.coobi.logistics.streamprocessor.kafka;

import com.coobi.logistics.streamprocessor.config.KafkaTopicsProperties;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Creates the topics this service reads or writes before the Streams topology starts
 * (MVP-2.2 and MVP-2.3).
 *
 * <p>Provisioning is idempotent: the existing topic names are listed first and only the
 * missing topics are created, so the input and dead letter topics provisioned by the
 * event generator are reused as they are, and the alert topic is created the first time
 * the processor runs. A topic created by a concurrent starter is accepted instead of
 * failing the startup.
 *
 * <p>It is a {@link SmartLifecycle} in the {@link #PHASE first} phase, which runs before
 * the {@code StreamsBuilderFactoryBean} of spring-kafka starts the topology, so no stream
 * thread starts before its topics exist.
 *
 * <p>A broker that is unreachable is retried a bounded number of times and then fails
 * startup, so the processor never runs without a destination for its alerts.
 */
@Component
public class KafkaTopicInitializer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KafkaTopicInitializer.class);

    /**
     * Phase of the provisioning step. The Kafka Streams topology starts in a much later
     * phase, so it starts only after the topics are in place.
     */
    public static final int PHASE = 0;

    private final Admin admin;
    private final KafkaTopicsProperties properties;

    private volatile boolean running;

    public KafkaTopicInitializer(Admin admin, KafkaTopicsProperties properties) {
        this.admin = admin;
        this.properties = properties;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public void start() {
        ensureTopics();
        running = true;
    }

    @Override
    public void stop() {
        // Nothing to release: the Admin client is closed by its own bean definition.
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public void ensureTopics() {
        if (!properties.getInitialization().isEnabled()) {
            log.warn("kafka topic initialization disabled, topics must exist already");
            return;
        }

        List<TopicSpec> specs = properties.topicSpecs();
        int maxAttempts = properties.getInitialization().getMaxAttempts();
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                createMissingTopics(specs);
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while initializing kafka topics", interrupted);
            } catch (Exception failure) {
                lastFailure = new IllegalStateException(
                        "kafka topic initialization failed on attempt " + attempt + " of " + maxAttempts, failure);
                log.warn(
                        "kafka topic initialization attempt failed attempt={} max-attempts={} reason={}",
                        attempt,
                        maxAttempts,
                        failure.getMessage());
                if (attempt < maxAttempts) {
                    sleep(properties.getInitialization().getRetryBackoffMillis());
                }
            }
        }
        throw lastFailure;
    }

    private void createMissingTopics(List<TopicSpec> specs) throws Exception {
        long timeoutMillis = properties.getInitialization().getRequestTimeoutMillis();
        Set<String> topicNames = specs.stream().map(TopicSpec::name).collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> existingNames = admin.listTopics().names().get(timeoutMillis, TimeUnit.MILLISECONDS);
        List<NewTopic> missing = specs.stream()
                .filter(spec -> !existingNames.contains(spec.name()))
                .map(TopicSpec::toNewTopic)
                .toList();

        if (missing.isEmpty()) {
            log.info("kafka topics already present topics={}", topicNames);
            return;
        }
        try {
            admin.createTopics(missing).all().get(timeoutMillis, TimeUnit.MILLISECONDS);
            log.info(
                    "kafka topics created topics={} partitions={} replication-factor={}",
                    missing.stream().map(NewTopic::name).toList(),
                    properties.getTopics().getPartitions(),
                    properties.getTopics().getReplicationFactor());
        } catch (ExecutionException alreadyExists) {
            if (!(alreadyExists.getCause() instanceof TopicExistsException)) {
                throw alreadyExists;
            }
            log.info("kafka topics were created concurrently topics={}", missing.stream().map(NewTopic::name).toList());
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry kafka topic initialization", interrupted);
        }
    }
}

package com.coobi.logistics.eventgenerator.kafka;

import com.coobi.logistics.eventgenerator.config.KafkaTopicsProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.TopicExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Creates the topics required by this service before the first event is published
 * (MVP-1.3).
 *
 * <p>Provisioning is idempotent: the existing topic names are listed first, only the
 * missing topics are created, and the resulting topics are compared with the configured
 * topology. An under-partitioned topic is expanded, a topic with more partitions than
 * configured is reported and left untouched (Kafka cannot shrink them), and a topic
 * created by a concurrent starter is accepted instead of failing the startup.
 *
 * <p>It is a {@link SmartLifecycle} in the first phase so that the whole topology is in
 * place before {@link com.coobi.logistics.eventgenerator.publisher.TelemetryPublishingScheduler},
 * which runs in a later phase, can start publishing. Provisioning happens while the
 * context is still starting, so nothing is published before the topics exist.
 *
 * <p>A broker that is unreachable is retried a bounded number of times and then fails
 * startup, so the generator never runs without a destination for its events.
 */
@Component
public class KafkaTopicInitializer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KafkaTopicInitializer.class);

    /**
     * Phase of the provisioning step. The publishing scheduler uses a higher phase, so it
     * starts only after this one has finished.
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
                applyTopology(specs);
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

    private void applyTopology(List<TopicSpec> specs) throws Exception {
        long timeoutMillis = properties.getInitialization().getRequestTimeoutMillis();
        Set<String> topicNames = specs.stream().map(TopicSpec::name).collect(Collectors.toCollection(LinkedHashSet::new));

        createMissingTopics(specs, topicNames, timeoutMillis);
        reconcilePartitions(specs, topicNames, timeoutMillis);
    }

    private void createMissingTopics(List<TopicSpec> specs, Set<String> topicNames, long timeoutMillis) throws Exception {
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

    private void reconcilePartitions(List<TopicSpec> specs, Set<String> topicNames, long timeoutMillis) throws Exception {
        Map<String, TopicDescription> descriptions = admin.describeTopics(topicNames)
                .allTopicNames()
                .get(timeoutMillis, TimeUnit.MILLISECONDS);

        Map<String, NewPartitions> expansions = new LinkedHashMap<>();
        for (TopicSpec spec : specs) {
            TopicDescription description = descriptions.get(spec.name());
            if (description == null) {
                continue;
            }
            int currentPartitions = description.partitions().size();
            if (currentPartitions < spec.partitions()) {
                expansions.put(spec.name(), NewPartitions.increaseTo(spec.partitions()));
            } else if (currentPartitions > spec.partitions()) {
                log.warn(
                        "kafka topic has more partitions than configured topic={} partitions={} configured-partitions={}",
                        spec.name(),
                        currentPartitions,
                        spec.partitions());
            }
        }
        if (expansions.isEmpty()) {
            log.info(
                    "kafka topics verified topics={} partitions={} replication-factor={}",
                    topicNames,
                    properties.getTopics().getPartitions(),
                    properties.getTopics().getReplicationFactor());
            return;
        }
        admin.createPartitions(expansions).all().get(timeoutMillis, TimeUnit.MILLISECONDS);
        log.info(
                "kafka topics expanded topics={} partitions={}",
                expansions.keySet(),
                properties.getTopics().getPartitions());
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

package com.coobi.logistics.streamprocessor.integration.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Reads one topic of a real broker from an integration test.
 *
 * <p>The probe is a plain consumer rather than a listener: a test asks for what has arrived,
 * and every call drains whatever the broker has for this probe. That keeps the integration
 * tests free of background threads and of fixed waits - the wait belongs to the assertion
 * (Awaitility polls the probe until the condition holds or the timeout expires).
 *
 * <p>The group is unique per probe and offsets are not committed, so two probes never
 * interfere with each other and a probe always reads from the beginning of the topic: the
 * records an integration test publishes before it starts polling are still observed.
 *
 * <p>It implements {@link AutoCloseable}, so a test can scope one probe per scenario.
 */
public final class KafkaTopicProbe implements AutoCloseable {

    /** How long one poll waits for records before returning what it has. */
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(250);

    private final KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> observed = new ArrayList<>();

    private KafkaTopicProbe(KafkaConsumer<String, String> consumer, String topic) {
        this.consumer = consumer;
        this.consumer.subscribe(List.of(topic));
    }

    /** A probe subscribed to {@code topic} of the broker at {@code bootstrapServers}. */
    public static KafkaTopicProbe subscribingTo(String bootstrapServers, String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "coobi-integration-probe-" + UUID.randomUUID());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return new KafkaTopicProbe(new KafkaConsumer<>(properties), topic);
    }

    /** One bounded poll, then everything this probe has observed so far. */
    public List<ConsumerRecord<String, String>> records() {
        consumer.poll(POLL_TIMEOUT).forEach(observed::add);
        return List.copyOf(observed);
    }

    /** The records whose message key is {@code key}, in the order they arrived. */
    public List<ConsumerRecord<String, String>> recordsWithKey(String key) {
        return records().stream().filter(record -> key.equals(record.key())).toList();
    }

    /** The newest record whose message key is {@code key}, when there is one. */
    public Optional<ConsumerRecord<String, String>> latestRecordWithKey(String key) {
        return recordsWithKey(key).stream().reduce((previous, latest) -> latest);
    }

    @Override
    public void close() {
        // wakeup releases a poll in progress; close then leaves the group without waiting for
        // the session timeout of a consumer nobody else is watching.
        consumer.wakeup();
        consumer.close(Duration.ofSeconds(5));
    }
}

package com.coobi.logistics.eventgenerator.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declarative definition of one Kafka topic.
 *
 * <p>Used as the single source of truth for topic creation, so the same names,
 * partition counts and replication factor are applied on every startup.
 *
 * @param name topic name
 * @param partitions number of partitions a topic must have
 * @param replicationFactor replication factor of the topic
 */
public record TopicSpec(String name, int partitions, short replicationFactor) {

    public TopicSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("topic name must not be blank");
        }
        if (partitions < 1) {
            throw new IllegalArgumentException("partitions must be at least 1 but was " + partitions);
        }
        if (replicationFactor < 1) {
            throw new IllegalArgumentException("replicationFactor must be at least 1 but was " + replicationFactor);
        }
    }

    public NewTopic toNewTopic() {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }
}

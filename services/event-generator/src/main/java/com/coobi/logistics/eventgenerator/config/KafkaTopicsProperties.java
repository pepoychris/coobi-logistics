package com.coobi.logistics.eventgenerator.config;

import com.coobi.logistics.eventgenerator.kafka.TopicSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Topic names and the development topology (MVP-1.3), plus the bounded retry policy
 * used while provisioning them.
 */
@ConfigurationProperties(prefix = "coobi.kafka")
@Validated
public class KafkaTopicsProperties {

    @NotNull
    @Valid
    private Topics topics = new Topics();

    @NotNull
    @Valid
    private Initialization initialization = new Initialization();

    public Topics getTopics() {
        return topics;
    }

    public void setTopics(Topics topics) {
        this.topics = topics;
    }

    public Initialization getInitialization() {
        return initialization;
    }

    public void setInitialization(Initialization initialization) {
        this.initialization = initialization;
    }

    /** Topic the generator publishes to. */
    public String locationTopic() {
        return topics.getLocation();
    }

    /** Both topics the service manages, in creation order. */
    public List<TopicSpec> topicSpecs() {
        return List.of(
                new TopicSpec(topics.getLocation(), topics.getPartitions(), topics.getReplicationFactor()),
                new TopicSpec(topics.getDeadLetter(), topics.getPartitions(), topics.getReplicationFactor()));
    }

    public static class Topics {

        @NotBlank
        private String location = "logistics.vehicle.location.v1";

        @NotBlank
        private String deadLetter = "logistics.vehicle.location.dlq.v1";

        @Min(1)
        @Max(1000)
        private int partitions = 6;

        @Min(1)
        @Max(10)
        private short replicationFactor = 1;

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public String getDeadLetter() {
            return deadLetter;
        }

        public void setDeadLetter(String deadLetter) {
            this.deadLetter = deadLetter;
        }

        public int getPartitions() {
            return partitions;
        }

        public void setPartitions(int partitions) {
            this.partitions = partitions;
        }

        public short getReplicationFactor() {
            return replicationFactor;
        }

        public void setReplicationFactor(short replicationFactor) {
            this.replicationFactor = replicationFactor;
        }
    }

    public static class Initialization {

        private boolean enabled = true;

        @Min(1)
        @Max(100)
        private int maxAttempts = 5;

        @Min(0)
        @Max(60_000)
        private long retryBackoffMillis = 3000;

        @Min(1000)
        @Max(120_000)
        private long requestTimeoutMillis = 15_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getRetryBackoffMillis() {
            return retryBackoffMillis;
        }

        public void setRetryBackoffMillis(long retryBackoffMillis) {
            this.retryBackoffMillis = retryBackoffMillis;
        }

        public long getRequestTimeoutMillis() {
            return requestTimeoutMillis;
        }

        public void setRequestTimeoutMillis(long requestTimeoutMillis) {
            this.requestTimeoutMillis = requestTimeoutMillis;
        }
    }
}

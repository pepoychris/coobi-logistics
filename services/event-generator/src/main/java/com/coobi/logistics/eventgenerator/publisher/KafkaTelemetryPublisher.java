package com.coobi.logistics.eventgenerator.publisher;

import com.coobi.logistics.eventgenerator.config.KafkaTopicsProperties;
import com.coobi.logistics.eventgenerator.event.VehicleLocationEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Publishes telemetry to the location topic using the vehicle identifier as the Kafka
 * key (Global Rule 15), so every event of a vehicle lands in the same partition and
 * keeps its relative order.
 *
 * <p>Sending is asynchronous: the tick that generates the batch is never blocked by the
 * broker. Every failure is counted, logged with the identifiers needed to find the
 * event, and never propagated, so one rejected record cannot stop the generator.
 *
 * <p>Shutdown drains the producer before the application exits (MVP-1.5).
 */
@Component
public class KafkaTelemetryPublisher implements TelemetryPublisher, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KafkaTelemetryPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final AtomicLong publishedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    public KafkaTelemetryPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            KafkaTopicsProperties topicsProperties,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topicsProperties.locationTopic();
        this.publishedCounter = Counter.builder("coobi.generator.events")
                .tag("topic", topic)
                .tag("result", "published")
                .description("Vehicle location events confirmed by the broker")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("coobi.generator.events")
                .tag("topic", topic)
                .tag("result", "failed")
                .description("Vehicle location events that could not be published")
                .register(meterRegistry);
    }

    @Override
    public void publish(VehicleLocationEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException serializationFailure) {
            failedCount.incrementAndGet();
            failedCounter.increment();
            log.error(
                    "telemetry event serialization failed event-id={} vehicle-id={} reason={}",
                    event.eventId(),
                    event.vehicleId(),
                    serializationFailure.getMessage(),
                    serializationFailure);
            return;
        }

        CompletableFuture<SendResult<String, String>> delivery =
                kafkaTemplate.send(topic, event.vehicleId(), payload);
        delivery.whenComplete((result, failure) -> {
            if (failure != null) {
                failedCount.incrementAndGet();
                failedCounter.increment();
                log.error(
                        "telemetry event publication failed event-id={} vehicle-id={} topic={} reason={}",
                        event.eventId(),
                        event.vehicleId(),
                        topic,
                        failure.getMessage(),
                        failure);
                return;
            }
            publishedCount.incrementAndGet();
            publishedCounter.increment();
            if (log.isDebugEnabled() && result != null) {
                log.debug(
                        "telemetry event published event-id={} vehicle-id={} partition={} offset={}",
                        event.eventId(),
                        event.vehicleId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }

    @Override
    public long publishedCount() {
        return publishedCount.get();
    }

    @Override
    public long failedCount() {
        return failedCount.get();
    }

    /** Drains in-flight records so a shutdown does not silently drop telemetry. */
    @Override
    public void destroy() {
        try {
            kafkaTemplate.flush();
        } catch (RuntimeException flushFailure) {
            log.warn("kafka producer flush during shutdown failed reason={}", flushFailure.getMessage(), flushFailure);
        }
        log.info("telemetry publisher stopped published-total={} failed-total={}", publishedCount(), failedCount());
    }
}

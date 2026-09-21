package com.coobi.logistics.eventgenerator.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.coobi.logistics.eventgenerator.config.KafkaTopicsProperties;
import com.coobi.logistics.eventgenerator.event.VehicleLocationData;
import com.coobi.logistics.eventgenerator.event.VehicleLocationEvent;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** MVP-1.5: the vehicle identifier is the Kafka key and failures are logged and counted. */
@ExtendWith(MockitoExtension.class)
class KafkaTelemetryPublisherTest {

    private static final String TOPIC = "logistics.vehicle.location.v1";

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final KafkaTopicsProperties topicsProperties = new KafkaTopicsProperties();

    private KafkaTelemetryPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaTelemetryPublisher(kafkaTemplate, jsonMapper(), topicsProperties, meterRegistry);
    }

    @Test
    void publishesJsonKeyedByVehicleId() {
        when(kafkaTemplate.send(eq(TOPIC), eq("TRUCK-00001"), anyString())).thenReturn(completedSend());
        VehicleLocationEvent event = event("TRUCK-00001");

        publisher.publish(event);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq("TRUCK-00001"), payload.capture());
        assertThat(payload.getValue())
                .contains("\"eventId\":\"" + event.eventId() + "\"")
                .contains("\"eventType\":\"VEHICLE_LOCATION_UPDATED\"")
                .contains("\"version\":1")
                .contains("\"vehicleId\":\"TRUCK-00001\"")
                .contains("\"timestamp\":\"2026-09-21T09:15:00.123Z\"")
                .contains("\"data\":{");
        assertThat(publisher.publishedCount()).isEqualTo(1);
        assertThat(publisher.failedCount()).isZero();
    }

    @Test
    void exposesThroughputCountersAsMetrics() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(completedSend());

        publisher.publish(event("TRUCK-00001"));

        assertThat(meterRegistry.get("coobi.generator.events").tag("result", "published").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void countsAndLogsDeliveryFailuresWithoutPropagatingThem() {
        CompletableFuture<SendResult<String, String>> failedDelivery = new CompletableFuture<>();
        failedDelivery.completeExceptionally(new RuntimeException("broker unavailable"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failedDelivery);
        ListAppender<ILoggingEvent> appender = attachAppender();

        assertThatCode(() -> publisher.publish(event("TRUCK-00007"))).doesNotThrowAnyException();

        assertThat(publisher.publishedCount()).isZero();
        assertThat(publisher.failedCount()).isEqualTo(1);
        assertThat(appender.list).anySatisfy(logged -> {
            assertThat(logged.getLevel()).isEqualTo(Level.ERROR);
            assertThat(logged.getFormattedMessage())
                    .contains("telemetry event publication failed")
                    .contains("TRUCK-00007");
            assertThat(logged.getThrowableProxy()).isNotNull();
        });
        assertThat(meterRegistry.get("coobi.generator.events").tag("result", "failed").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void drainsTheProducerOnShutdown() {
        publisher.destroy();

        verify(kafkaTemplate).flush();
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(KafkaTelemetryPublisher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static CompletableFuture<SendResult<String, String>> completedSend() {
        @SuppressWarnings("unchecked")
        SendResult<String, String> result = mock(SendResult.class);
        return CompletableFuture.completedFuture(result);
    }

    private static VehicleLocationEvent event(String vehicleId) {
        return VehicleLocationEvent.create(
                vehicleId, Instant.parse("2026-09-21T09:15:00.123Z"), new VehicleLocationData(39.4699, -0.3763, 82.3, 214.5));
    }

    private static JsonMapper jsonMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}

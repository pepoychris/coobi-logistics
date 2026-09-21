package com.coobi.logistics.eventgenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coobi.logistics.eventgenerator.config.KafkaTopicsProperties;
import com.coobi.logistics.eventgenerator.publisher.TelemetryPublisher;
import com.coobi.logistics.eventgenerator.publisher.TelemetryPublishingScheduler;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * MVP-1.5: closing the application context cancels publishing and drains the producer.
 * That is the path a termination signal takes in a deployed process.
 *
 * <p>The context is built and closed by the test itself, because the Spring test
 * framework does not support closing a shared context from inside a test method.
 *
 * <p>The offline overrides below are command-line arguments, not builder defaults:
 * default properties have the lowest precedence, so {@code application.yml} would win
 * over them and the context would try to reach a real broker. Command-line arguments
 * rank above the configuration files, which is what makes the offline switches
 * effective.
 */
class GracefulShutdownTests {

    /** Command-line arguments: precedence above the packaged {@code application.yml}. */
    private static final String[] OFFLINE_OVERRIDES = {
        "--coobi.generator.publish-enabled=true",
        "--coobi.generator.vehicle-count=10",
        "--coobi.generator.target-events-per-second=100",
        "--coobi.generator.tick-interval-millis=10",
        "--coobi.kafka.initialization.enabled=false",
        "--management.health.kafka.enabled=false"
    };

    @Test
    void cancelsPublishingAndDrainsTheProducerWhenTheContextCloses() throws InterruptedException {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                        EventGeneratorApplication.class, StubbedProducer.class)
                .web(WebApplicationType.NONE)
                .run(OFFLINE_OVERRIDES)) {
            TelemetryPublishingScheduler scheduler = context.getBean(TelemetryPublishingScheduler.class);
            TelemetryPublisher publisher = context.getBean(TelemetryPublisher.class);
            KafkaTemplate<String, String> kafkaTemplate = context.getBean(KafkaTemplate.class);
            KafkaTopicsProperties topicsProperties = context.getBean(KafkaTopicsProperties.class);

            // The override reached the context with effect: no broker was contacted.
            assertThat(topicsProperties.getInitialization().isEnabled()).isFalse();
            assertThat(context.isActive()).isTrue();
            assertThat(scheduler.isRunning()).isTrue();

            awaitFirstPublish(publisher);

            context.close();

            assertThat(context.isActive()).isFalse();
            assertThat(scheduler.isRunning()).isFalse();
            verify(kafkaTemplate, atLeastOnce()).flush();
        }
    }

    /** The shutdown assertions are only meaningful once a tick has really published. */
    private static void awaitFirstPublish(TelemetryPublisher publisher) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (publisher.publishedCount() == 0 && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10);
        }
        assertThat(publisher.publishedCount())
                .as("events published before the context was closed")
                .isPositive();
    }

    @Configuration(proxyBeanMethods = false)
    static class StubbedProducer {

        @Bean
        @Primary
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> stubbedKafkaTemplate() {
            SendResult<String, String> delivered = mock(SendResult.class);
            CompletableFuture<SendResult<String, String>> delivery = CompletableFuture.completedFuture(delivered);
            KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
            when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(delivery);
            return kafkaTemplate;
        }
    }
}

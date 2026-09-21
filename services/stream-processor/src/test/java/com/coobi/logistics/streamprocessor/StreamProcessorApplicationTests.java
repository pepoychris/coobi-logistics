package com.coobi.logistics.streamprocessor;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.streamprocessor.config.KafkaTopicsProperties;
import com.coobi.logistics.streamprocessor.config.ProcessingProperties;
import org.apache.kafka.streams.kstream.KStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * MVP-2.1: the application builds its topology and reports its state without a broker.
 *
 * <p>The topology is built but never started, topic provisioning is disabled and the
 * Kafka health indicator is disabled, because it would report the absent broker instead
 * of the service state.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.streams.auto-startup=false",
            "coobi.kafka.initialization.enabled=false",
            "management.health.kafka.enabled=false"
        })
class StreamProcessorApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProcessingProperties processingProperties;

    @Autowired
    private KafkaTopicsProperties kafkaTopicsProperties;

    @Autowired
    private KStream<String, String> telemetryProcessingTopology;

    @Test
    void exposesAHealthEndpoint() {
        ResponseEntity<String> health = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void bindsTheDocumentedDetectionThreshold() {
        assertThat(processingProperties.getSpeedLimitKph()).isEqualTo(120.0);
    }

    @Test
    void bindsTheDocumentedStoppedThresholds() {
        assertThat(processingProperties.getStoppedWindowSeconds()).isEqualTo(300L);
        assertThat(processingProperties.getMovementThresholdMeters()).isEqualTo(50.0);
    }

    @Test
    void readsAndPublishesToTheVersionedTopics() {
        assertThat(kafkaTopicsProperties.locationTopic()).isEqualTo("logistics.vehicle.location.v1");
        assertThat(kafkaTopicsProperties.deadLetterTopic()).isEqualTo("logistics.vehicle.location.dlq.v1");
        assertThat(kafkaTopicsProperties.alertTopic()).isEqualTo("logistics.alert.v1");
    }

    @Test
    void buildsTheStreamsTopology() {
        assertThat(telemetryProcessingTopology).isNotNull();
    }
}

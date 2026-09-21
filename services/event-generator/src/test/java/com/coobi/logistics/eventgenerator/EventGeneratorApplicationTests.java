package com.coobi.logistics.eventgenerator;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.eventgenerator.config.GeneratorMode;
import com.coobi.logistics.eventgenerator.config.GeneratorProperties;
import com.coobi.logistics.eventgenerator.config.KafkaTopicsProperties;
import com.coobi.logistics.eventgenerator.publisher.TelemetryPublisher;
import com.coobi.logistics.eventgenerator.simulation.VehicleTelemetrySimulator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * MVP-1.1: the application starts and reports its state without a Kafka broker.
 *
 * <p>Topic provisioning and publishing are disabled so the context can start without
 * infrastructure; the Kafka health indicator is disabled because it would report the
 * absent broker instead of the service state.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "coobi.generator.publish-enabled=false",
            "coobi.kafka.initialization.enabled=false",
            "management.health.kafka.enabled=false"
        })
class EventGeneratorApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private GeneratorProperties generatorProperties;

    @Autowired
    private KafkaTopicsProperties kafkaTopicsProperties;

    @Autowired
    private VehicleTelemetrySimulator simulator;

    @Autowired
    private TelemetryPublisher publisher;

    @Autowired
    private ProducerFactory<String, String> telemetryProducerFactory;

    @Autowired
    private ObjectProvider<DefaultKafkaProducerFactoryCustomizer> producerFactoryCustomizers;

    @Test
    void exposesAHealthEndpoint() {
        ResponseEntity<String> health = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
    }

    /**
     * MVP-8.1 and MVP-8.2: the counters of the publisher and the JVM metrics of this service
     * are published in the format Prometheus scrapes.
     */
    @Test
    void exposesThePrometheusEndpoint() {
        ResponseEntity<String> prometheus = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getBody())
                .contains("# TYPE coobi_generator_events_total counter")
                .contains("result=\"published\"")
                .contains("result=\"failed\"")
                .contains("application=\"event-generator\"");
    }

    /**
     * MVP-8.3: the Kafka client metrics of this service come from the Micrometer listener the
     * auto-configuration attaches to the producer factory, so the explicitly declared factory
     * has to take the customizers of the context. Asserting the listener is attached is
     * asserting the wiring, because a producer cannot be created without a broker.
     */
    @Test
    void attachesTheProducerMetricsBinderToTheTelemetryProducer() {
        assertThat(producerFactoryCustomizers.orderedStream()).isNotEmpty();
        assertThat(telemetryProducerFactory).isInstanceOf(DefaultKafkaProducerFactory.class);
        assertThat(((DefaultKafkaProducerFactory<?, ?>) telemetryProducerFactory).getListeners())
                .anySatisfy(listener -> assertThat(listener).isInstanceOf(MicrometerProducerListener.class));
    }

    @Test
    void startsTheNormalModeFleet() {
        assertThat(generatorProperties.getMode()).isEqualTo(GeneratorMode.NORMAL);
        assertThat(generatorProperties.effectiveVehicleCount()).isEqualTo(1000);
        assertThat(generatorProperties.effectiveTargetEventsPerSecond()).isEqualTo(1000);
        assertThat(simulator.vehicleCount()).isEqualTo(1000);
        assertThat(generatorProperties.isPublishEnabled()).isFalse();
        assertThat(publisher.publishedCount()).isZero();
        assertThat(publisher.failedCount()).isZero();
    }

    @Test
    void publishesToTheVersionedLocationTopic() {
        assertThat(kafkaTopicsProperties.locationTopic()).isEqualTo("logistics.vehicle.location.v1");
        assertThat(kafkaTopicsProperties.topicSpecs())
                .extracting(spec -> spec.name())
                .containsExactly("logistics.vehicle.location.v1", "logistics.vehicle.location.dlq.v1");
        assertThat(kafkaTopicsProperties.topicSpecs()).allSatisfy(spec -> {
            assertThat(spec.partitions()).isEqualTo(6);
            assertThat(spec.replicationFactor()).isEqualTo((short) 1);
        });
    }
}

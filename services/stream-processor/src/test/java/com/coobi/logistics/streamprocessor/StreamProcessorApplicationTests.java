package com.coobi.logistics.streamprocessor;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.streamprocessor.config.KafkaTopicsProperties;
import com.coobi.logistics.streamprocessor.config.ProcessingProperties;
import com.coobi.logistics.streamprocessor.persistence.JdbcTelemetryPersistence;
import com.coobi.logistics.streamprocessor.persistence.TelemetryPersistence;
import java.util.List;
import java.util.Locale;
import org.apache.kafka.streams.kstream.KStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.StreamsBuilderFactoryBeanCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * MVP-2.1: the application builds its topology and reports its state without a broker.
 *
 * <p>The topology is built but never started, topic provisioning is disabled and the
 * Kafka health indicator is disabled, because it would report the absent broker instead
 * of the service state. The persistence layer is wired against an in-memory database, so the
 * context can be built without a PostgreSQL server (the migrations themselves are covered by
 * {@code JdbcTelemetryPersistenceTest}, which applies them for real).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.streams.auto-startup=false",
            "coobi.kafka.initialization.enabled=false",
            "management.health.kafka.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:stream-processor-app-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.enabled=false"
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

    @Autowired
    private TelemetryPersistence telemetryPersistence;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void exposesAHealthEndpoint() {
        ResponseEntity<String> health = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
    }

    /**
     * MVP-8.1 and MVP-8.2: the custom metrics and the JVM metrics of this service are
     * published in the format Prometheus scrapes.
     */
    @Test
    void exposesThePrometheusEndpoint() {
        ResponseEntity<String> prometheus = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getBody())
                .contains("# TYPE logistics_events_received_total counter")
                .contains("# TYPE logistics_events_processed_total counter")
                .contains("# TYPE logistics_events_failed_total counter")
                .contains("# TYPE logistics_alerts_generated_total counter")
                // The duration is rendered with its base unit in the name, as Prometheus
                // expects of a duration; `/actuator/metrics` keeps the contract name.
                .contains("# TYPE logistics_event_processing_duration_seconds summary")
                .contains("application=\"stream-processor\"");
    }

    /**
     * MVP-8.1: the contract names are the names of the meters, which is what the JSON view of
     * Actuator reports and what a Prometheus query is written against.
     */
    @Test
    void exposesTheContractMetricsByTheirExactNames() {
        ResponseEntity<String> received =
                restTemplate.getForEntity("/actuator/metrics/logistics_events_received_total", String.class);
        ResponseEntity<String> duration =
                restTemplate.getForEntity("/actuator/metrics/logistics_event_processing_duration", String.class);

        assertThat(received.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(received.getBody()).contains("\"name\":\"logistics_events_received_total\"");
        assertThat(duration.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(duration.getBody())
                .contains("\"name\":\"logistics_event_processing_duration\"")
                .contains("\"statistic\":\"COUNT\"");
    }

    /**
     * MVP-8.3: Spring Boot attaches its Micrometer binder to the streams of this application,
     * which is where the Kafka Streams metrics of the service come from. The binder is a
     * customizer of the streams factory bean, so asserting the customizer is in the context is
     * asserting that the metrics are wired without starting a broker.
     */
    @Test
    void registersTheKafkaStreamsMetricsBinder() {
        assertThat(applicationContext.getBean("kafkaStreamsMetrics", StreamsBuilderFactoryBeanCustomizer.class))
                .isNotNull();
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

    /** MVP-4.1: the persistence port is wired into the application, over JDBC. */
    @Test
    void wiresTheJdbcPersistencePort() {
        assertThat(telemetryPersistence).isInstanceOf(JdbcTelemetryPersistence.class);
    }

    /** MVP-4.1: no ORM owns the schema, so there is no automatic schema management. */
    @Test
    void leavesSchemaManagementToFlyway() {
        List<String> beanNames = List.of(applicationContext.getBeanDefinitionNames());
        assertThat(beanNames)
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("entitymanager"));
    }
}

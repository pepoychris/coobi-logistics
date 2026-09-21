package com.coobi.logistics.eventgenerator;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.eventgenerator.config.KafkaClientConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

/**
 * MVP-8.3: the Kafka client metrics of the producer really reach the meter registry.
 *
 * <p>The producer is built from the factory of this service, with the customizers of the
 * application applied, and points at an address where no broker answers: a producer publishes
 * its metrics as soon as it is created, so the binding is verifiable without a broker. The
 * producer is closed and the factory destroyed at the end, so the test leaves no client
 * behind.
 */
@SpringBootTest(
        properties = {
            "coobi.generator.publish-enabled=false",
            "coobi.kafka.initialization.enabled=false",
            "management.health.kafka.enabled=false"
        })
class KafkaProducerMetricsTests {

    @Autowired
    private ObjectProvider<DefaultKafkaProducerFactoryCustomizer> producerFactoryCustomizers;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void bindsTheKafkaProducerMetricsOfACreatedProducer() {
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(List.of("localhost:1"));
        DefaultKafkaProducerFactory<String, String> factory =
                (DefaultKafkaProducerFactory<String, String>) new KafkaClientConfiguration()
                        .telemetryProducerFactory(kafkaProperties, producerFactoryCustomizers);

        Producer<String, String> producer = factory.createProducer();
        Set<String> kafkaMeters = kafkaMeterNames(meterRegistry);
        producer.close();
        factory.destroy();

        // The signals MVP-8.3 asks for on this side of the pipeline: records per second, the
        // errors of the producer, and the requests it has in flight. The names are the
        // Micrometer names of the Kafka client metrics; Prometheus renders them with dots
        // replaced by underscores.
        assertThat(kafkaMeters)
                .contains(
                        "kafka.producer.record.send.total",
                        "kafka.producer.record.send.rate",
                        "kafka.producer.record.error.total",
                        "kafka.producer.record.error.rate",
                        "kafka.producer.request.total",
                        "kafka.producer.requests.in.flight");
    }

    private static Set<String> kafkaMeterNames(MeterRegistry registry) {
        return registry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .filter(name -> name.startsWith("kafka"))
                .collect(Collectors.toSet());
    }
}

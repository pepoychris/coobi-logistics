package com.coobi.logistics.streamprocessor.processing;

import com.coobi.logistics.streamprocessor.config.KafkaTopicsProperties;
import com.coobi.logistics.streamprocessor.config.ProcessingProperties;
import com.coobi.logistics.streamprocessor.event.DeadLetterEvent;
import com.coobi.logistics.streamprocessor.event.VehicleLocationEvent;
import com.coobi.logistics.streamprocessor.persistence.TelemetryPersistence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Kafka Streams topology of the processor (MVP-2.1 to MVP-2.4 and MVP-3.1 to MVP-3.2).
 *
 * <pre>
 * logistics.vehicle.location.v1
 *   -> telemetry-validation          inspect(key, payload)
 *      -> accepted-telemetry         valid events
 *         -> accepted-events         deserialize once
 *            -> speeding-detection   NORMAL -&gt; SPEEDING transition
 *            -> vehicle-state        latest state per vehicle, MOVING -&gt; STOPPED
 *               -> alerts            -> logistics.alert.v1
 *      -> rejected-telemetry         invalid events
 *         -> dead-letter-events      originalEvent / error / failedAt / sourceTopic
 *            -> logistics.vehicle.location.dlq.v1
 * </pre>
 *
 * <p>Validation happens once, in the validation node, and the two branches only read its
 * result. Both branches are terminal sinks, so an invalid record cannot influence the
 * records behind it and the stream keeps processing after a rejection.
 *
 * <p>The accepted events feed two independent detections. Both are stateful and keyed by
 * {@code vehicleId}, each with its own state store, and both publish to the same alert
 * topic through one merged sink, so the alert contract and the topic wiring stay unchanged
 * while the two state machines can never interfere with each other.
 *
 * <p>Both detections also write the derived state to PostgreSQL through
 * {@link TelemetryPersistence} (MVP-4): the state branch overwrites the latest state of the
 * vehicle and both branches store the alerts they accept. The write is synchronous and
 * idempotent, which is the trade-off documented in {@code docs/stream-processor.md}: it
 * keeps the sequence "derive, store, publish" in one thread - and therefore reproducible -
 * at the cost of tying the throughput of the stream to the database.
 *
 * <p>Every stage is instrumented with {@link TelemetryMetrics} (MVP-8.1): a consumed record
 * increments the received counter, the accepted and rejected branches increment one counter
 * each, and the validation, detection and dead letter steps are timed. The counters are
 * incremented on this path and nowhere else, so a metric that stays at zero means no record
 * reached the stage instead of a stage nobody wired.
 */
@Configuration(proxyBeanMethods = false)
public class TelemetryTopologyConfiguration {

    /**
     * The metrics of the topology (MVP-8.1). One instance is shared by the stages of every
     * stream thread, because Micrometer meters are thread-safe and the counters of a metric
     * belong to the metric rather than to the thread that happened to increment it.
     */
    @Bean
    public TelemetryMetrics telemetryMetrics(MeterRegistry meterRegistry) {
        return new TelemetryMetrics(meterRegistry);
    }

    /**
     * Wires the topology. The returned stream is the alert stream: declaring it as a bean
     * is what tells spring-kafka to build this topology with the auto-configured
     * {@link StreamsBuilder}.
     */
    @Bean
    public KStream<String, String> telemetryProcessingTopology(
            StreamsBuilder streamsBuilder,
            ObjectMapper objectMapper,
            Validator validator,
            ProcessingProperties processingProperties,
            KafkaTopicsProperties topics,
            Clock processingClock,
            TelemetryPersistence persistence,
            TelemetryMetrics metrics) {

        TelemetryInspector inspector = new TelemetryInspector(objectMapper, validator);
        StoppedVehicleDetector stoppedVehicleDetector = new StoppedVehicleDetector(
                Duration.ofSeconds(processingProperties.getStoppedWindowSeconds()),
                processingProperties.getMovementThresholdMeters());

        streamsBuilder.addStateStore(SpeedingAlertProcessor.stateStore());
        streamsBuilder.addStateStore(VehicleStateProcessor.stateStore());

        KStream<String, TelemetryInspection> inspected = streamsBuilder
                .stream(topics.locationTopic(), Consumed.with(Serdes.String(), Serdes.String()))
                .mapValues((key, payload) -> inspect(inspector, metrics, key, payload), Named.as("telemetry-validation"));

        KStream<String, TelemetryInspection> accepted = inspected.filter(
                (key, inspection) -> inspection.isValid(), Named.as("accepted-telemetry"));
        KStream<String, TelemetryInspection> rejected = inspected.filterNot(
                (key, inspection) -> inspection.isValid(), Named.as("rejected-telemetry"));

        KStream<String, VehicleLocationEvent> acceptedEvents =
                accepted.mapValues((key, inspection) -> inspection.event(), Named.as("accepted-events"));

        KStream<String, String> speedingAlerts = acceptedEvents.process(
                SpeedingAlertProcessor.supplier(
                        processingProperties.getSpeedLimitKph(), objectMapper, persistence, metrics),
                Named.as(SpeedingAlertProcessor.PROCESSOR_NAME),
                SpeedingAlertProcessor.STATE_STORE_NAME);

        KStream<String, String> stoppedAlerts = acceptedEvents.process(
                VehicleStateProcessor.supplier(stoppedVehicleDetector, objectMapper, persistence, metrics),
                Named.as(VehicleStateProcessor.PROCESSOR_NAME),
                VehicleStateProcessor.STATE_STORE_NAME);

        KStream<String, String> alerts = speedingAlerts.merge(stoppedAlerts, Named.as("alerts"));
        alerts.to(topics.alertTopic(), Produced.with(Serdes.String(), Serdes.String()));

        rejected
                .mapValues(
                        (key, inspection) -> metrics.processingDuration()
                                .record(() -> deadLetter(
                                        objectMapper, processingClock, topics.locationTopic(), inspection)),
                        Named.as("dead-letter-events"))
                .to(topics.deadLetterTopic(), Produced.with(Serdes.String(), Serdes.String()));

        return alerts;
    }

    /**
     * The validation node: one consumed record is counted as received, the inspection is
     * timed, and its outcome is counted as processed or failed. The outcome counters are
     * incremented here instead of in the branches, so a record whose branch is never reached
     * cannot be counted twice.
     */
    private static TelemetryInspection inspect(
            TelemetryInspector inspector, TelemetryMetrics metrics, String key, String payload) {
        metrics.received();
        TelemetryInspection inspection =
                metrics.processingDuration().record(() -> inspector.inspect(key, payload));
        if (inspection.isValid()) {
            metrics.processed();
        } else {
            metrics.failed();
        }
        return inspection;
    }

    private static String deadLetter(
            ObjectMapper objectMapper, Clock clock, String sourceTopic, TelemetryInspection inspection) {
        DeadLetterEvent event =
                new DeadLetterEvent(inspection.originalEvent(), inspection.error(), clock.instant(), sourceTopic);
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("serializing the dead letter event failed", failure);
        }
    }
}

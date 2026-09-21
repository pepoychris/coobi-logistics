package com.coobi.logistics.streamprocessor.processing;

import com.coobi.logistics.streamprocessor.config.KafkaTopicsProperties;
import com.coobi.logistics.streamprocessor.config.ProcessingProperties;
import com.coobi.logistics.streamprocessor.event.DeadLetterEvent;
import com.coobi.logistics.streamprocessor.event.VehicleLocationEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
@Configuration(proxyBeanMethods = false)
public class TelemetryTopologyConfiguration {

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
            Clock processingClock) {

        TelemetryInspector inspector = new TelemetryInspector(objectMapper, validator);
        StoppedVehicleDetector stoppedVehicleDetector = new StoppedVehicleDetector(
                Duration.ofSeconds(processingProperties.getStoppedWindowSeconds()),
                processingProperties.getMovementThresholdMeters());

        streamsBuilder.addStateStore(SpeedingAlertProcessor.stateStore());
        streamsBuilder.addStateStore(VehicleStateProcessor.stateStore());

        KStream<String, TelemetryInspection> inspected = streamsBuilder
                .stream(topics.locationTopic(), Consumed.with(Serdes.String(), Serdes.String()))
                .mapValues((key, payload) -> inspector.inspect(key, payload), Named.as("telemetry-validation"));

        KStream<String, TelemetryInspection> accepted = inspected.filter(
                (key, inspection) -> inspection.isValid(), Named.as("accepted-telemetry"));
        KStream<String, TelemetryInspection> rejected = inspected.filterNot(
                (key, inspection) -> inspection.isValid(), Named.as("rejected-telemetry"));

        KStream<String, VehicleLocationEvent> acceptedEvents =
                accepted.mapValues((key, inspection) -> inspection.event(), Named.as("accepted-events"));

        KStream<String, String> speedingAlerts = acceptedEvents.process(
                SpeedingAlertProcessor.supplier(processingProperties.getSpeedLimitKph(), objectMapper),
                Named.as(SpeedingAlertProcessor.PROCESSOR_NAME),
                SpeedingAlertProcessor.STATE_STORE_NAME);

        KStream<String, String> stoppedAlerts = acceptedEvents.process(
                VehicleStateProcessor.supplier(stoppedVehicleDetector, objectMapper),
                Named.as(VehicleStateProcessor.PROCESSOR_NAME),
                VehicleStateProcessor.STATE_STORE_NAME);

        KStream<String, String> alerts = speedingAlerts.merge(stoppedAlerts, Named.as("alerts"));
        alerts.to(topics.alertTopic(), Produced.with(Serdes.String(), Serdes.String()));

        rejected
                .mapValues(
                        (key, inspection) -> deadLetter(
                                objectMapper, processingClock, topics.locationTopic(), inspection),
                        Named.as("dead-letter-events"))
                .to(topics.deadLetterTopic(), Produced.with(Serdes.String(), Serdes.String()));

        return alerts;
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

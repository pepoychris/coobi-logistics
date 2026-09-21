package com.coobi.logistics.streamprocessor.processing;

import com.coobi.logistics.streamprocessor.event.AlertData;
import com.coobi.logistics.streamprocessor.event.AlertEvent;
import com.coobi.logistics.streamprocessor.event.AlertType;
import com.coobi.logistics.streamprocessor.event.VehicleLocationEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the per-vehicle speed state machine into alerts (MVP-2.3).
 *
 * <p>The state is keyed by {@code vehicleId} and lives in a Kafka Streams state store, not
 * in the processor instance: a restart continues from the committed state instead of
 * re-emitting an alert for a vehicle that was already speeding. Records that stay in the
 * same state are dropped without touching the store, so the transition into
 * {@link VehicleSpeedState#SPEEDING} is observed exactly once.
 *
 * <p>The alert is forwarded as its serialized JSON document, keyed by {@code vehicleId}
 * (Global Rule 15). A document that cannot be serialized is logged and dropped instead of
 * failing the stream: the alert contract is built locally, so a failure there is a defect
 * to investigate, not an input problem to route to the dead letter topic.
 */
public final class SpeedingAlertProcessor implements Processor<String, VehicleLocationEvent, String, String> {

    public static final String PROCESSOR_NAME = "speeding-detection";
    public static final String STATE_STORE_NAME = "vehicle-speed-state";

    private static final Logger log = LoggerFactory.getLogger(SpeedingAlertProcessor.class);

    private final double speedLimitKph;
    private final ObjectMapper objectMapper;

    private ProcessorContext<String, String> context;
    private KeyValueStore<String, String> state;

    public SpeedingAlertProcessor(double speedLimitKph, ObjectMapper objectMapper) {
        this.speedLimitKph = speedLimitKph;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * The keyed, changelog-backed store that survives a restart. It is logged by default,
     * so the state is restored from Kafka and a replayed record cannot produce a second
     * alert for the same crossing.
     */
    public static StoreBuilder<KeyValueStore<String, String>> stateStore() {
        return Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STATE_STORE_NAME), Serdes.String(), Serdes.String());
    }

    public static ProcessorSupplier<String, VehicleLocationEvent, String, String> supplier(
            double speedLimitKph, ObjectMapper objectMapper) {
        return () -> new SpeedingAlertProcessor(speedLimitKph, objectMapper);
    }

    @Override
    public void init(ProcessorContext<String, String> context) {
        this.context = context;
        this.state = context.getStateStore(STATE_STORE_NAME);
    }

    @Override
    public void process(Record<String, VehicleLocationEvent> record) {
        VehicleLocationEvent event = record.value();
        String vehicleId = event.vehicleId();
        VehicleSpeedState previous = VehicleSpeedState.from(state.get(vehicleId));
        VehicleSpeedState current =
                event.data().speed() > speedLimitKph ? VehicleSpeedState.SPEEDING : VehicleSpeedState.NORMAL;

        if (previous == current) {
            return;
        }
        state.put(vehicleId, current.name());

        if (current == VehicleSpeedState.SPEEDING) {
            alert(record, event);
            return;
        }
        log.debug(
                "vehicle back below the speed limit vehicle-id={} speed={} speed-limit={}",
                vehicleId,
                event.data().speed(),
                speedLimitKph);
    }

    private void alert(Record<String, VehicleLocationEvent> record, VehicleLocationEvent event) {
        AlertEvent alert = AlertEvent.create(
                AlertType.SPEEDING,
                event.vehicleId(),
                event.timestamp(),
                new AlertData(event.data().speed(), speedLimitKph));

        String payload;
        try {
            payload = objectMapper.writeValueAsString(alert);
        } catch (JsonProcessingException failure) {
            log.error(
                    "dropping an alert that could not be serialized event-id={} vehicle-id={}",
                    alert.eventId(),
                    event.vehicleId(),
                    failure);
            return;
        }

        context.forward(new Record<>(event.vehicleId(), payload, record.timestamp(), record.headers()));
        log.info(
                "speeding detected vehicle-id={} speed={} speed-limit={} alert-id={}",
                event.vehicleId(),
                event.data().speed(),
                speedLimitKph,
                alert.eventId());
    }
}

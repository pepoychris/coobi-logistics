package com.coobi.logistics.streamprocessor.processing;

import com.coobi.logistics.streamprocessor.event.AlertData;
import com.coobi.logistics.streamprocessor.event.AlertEvent;
import com.coobi.logistics.streamprocessor.event.AlertType;
import com.coobi.logistics.streamprocessor.event.VehicleLocationEvent;
import com.coobi.logistics.streamprocessor.persistence.TelemetryPersistence;
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
 * Keeps the latest telemetry state of every vehicle and turns the {@code MOVING} to
 * {@code STOPPED} transition into an alert (MVP-3.1 and MVP-3.2).
 *
 * <p>The state is keyed by {@code vehicleId} and lives in a Kafka Streams state store, not
 * in the processor instance: a restart continues from the committed state - restored from
 * the changelog - instead of restarting the stationary window of every parked vehicle.
 * Every accepted record is written to the store, so what a downstream consumer reads for a
 * vehicle is its latest state, and records of one vehicle are processed in the order of
 * their partition, which is the order the generator published them in.
 *
 * <p>The alert is forwarded as its serialized JSON document, keyed by {@code vehicleId}
 * (Global Rule 15), and only when the store says the vehicle was moving before this event:
 * telemetry of an already stopped vehicle is written to the store but produces no alert.
 *
 * <p>Every accepted record also overwrites the persisted latest state of the vehicle, and a
 * stopped alert is persisted before it is published (MVP-4.2 and MVP-4.3). The write happens
 * on the stream thread and is idempotent, so a record that Kafka Streams retries after a
 * database failure leaves the same rows behind as the first attempt would have.
 *
 * <p>A state document that cannot be read back is logged and treated as absent, so a
 * single damaged entry restarts the window of one vehicle instead of failing the stream
 * for every vehicle behind it.
 *
 * <p>Every record of this step is timed and every forwarded alert is counted (MVP-8.1), so
 * the state machine, the state-store write, the database write and the alert contract are all
 * inside the measured window.
 */
public final class VehicleStateProcessor implements Processor<String, VehicleLocationEvent, String, String> {

    public static final String PROCESSOR_NAME = "vehicle-state";
    public static final String STATE_STORE_NAME = "vehicle-state-store";

    private static final Logger log = LoggerFactory.getLogger(VehicleStateProcessor.class);

    private final StoppedVehicleDetector detector;
    private final ObjectMapper objectMapper;
    private final TelemetryPersistence persistence;
    private final TelemetryMetrics metrics;

    private ProcessorContext<String, String> context;
    private KeyValueStore<String, String> state;

    public VehicleStateProcessor(
            StoppedVehicleDetector detector,
            ObjectMapper objectMapper,
            TelemetryPersistence persistence,
            TelemetryMetrics metrics) {
        this.detector = Objects.requireNonNull(detector, "detector must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.persistence = Objects.requireNonNull(persistence, "persistence must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    /**
     * The keyed, changelog-backed store that survives a restart. It is logged by default,
     * so the latest state of every vehicle is restored from Kafka and an event replayed
     * after a restart is judged against the state it was judged against the first time.
     */
    public static StoreBuilder<KeyValueStore<String, String>> stateStore() {
        return Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STATE_STORE_NAME), Serdes.String(), Serdes.String());
    }

    public static ProcessorSupplier<String, VehicleLocationEvent, String, String> supplier(
            StoppedVehicleDetector detector,
            ObjectMapper objectMapper,
            TelemetryPersistence persistence,
            TelemetryMetrics metrics) {
        return () -> new VehicleStateProcessor(detector, objectMapper, persistence, metrics);
    }

    @Override
    public void init(ProcessorContext<String, String> context) {
        this.context = context;
        this.state = context.getStateStore(STATE_STORE_NAME);
    }

    @Override
    public void process(Record<String, VehicleLocationEvent> record) {
        metrics.processingDuration().record(() -> advance(record));
    }

    private void advance(Record<String, VehicleLocationEvent> record) {
        VehicleLocationEvent event = record.value();
        String vehicleId = event.vehicleId();
        VehicleState previous = read(vehicleId);
        VehicleState current = detector.advance(previous, event);
        state.put(vehicleId, write(vehicleId, current));
        persistence.recordVehicleState(vehicleId, current);

        if (previous != null
                && previous.status() == VehicleStatus.MOVING
                && current.status() == VehicleStatus.STOPPED) {
            alert(record, event);
        }
    }

    private VehicleState read(String vehicleId) {
        String stored = state.get(vehicleId);
        if (stored == null) {
            return null;
        }
        try {
            return objectMapper.readValue(stored, VehicleState.class);
        } catch (JsonProcessingException unreadable) {
            log.error(
                    "restarting the stationary window of a vehicle whose state could not be read vehicle-id={}",
                    vehicleId,
                    unreadable);
            return null;
        }
    }

    private String write(String vehicleId, VehicleState vehicleState) {
        try {
            return objectMapper.writeValueAsString(vehicleState);
        } catch (JsonProcessingException failure) {
            // The document is built locally, so a failure here is a defect to investigate
            // rather than an input problem to route to the dead letter topic.
            throw new IllegalStateException("serializing the vehicle state of " + vehicleId + " failed", failure);
        }
    }

    private void alert(Record<String, VehicleLocationEvent> record, VehicleLocationEvent event) {
        AlertEvent alert = AlertEvent.create(
                AlertType.VEHICLE_STOPPED,
                event.vehicleId(),
                event.timestamp(),
                new AlertData(event.data().speed(), detector.movementThresholdMeters()));

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

        // Stored before it is published: the write is idempotent, so a retry of this record
        // cannot leave an alert in the topic without its row.
        persistence.recordAlert(alert);
        context.forward(new Record<>(event.vehicleId(), payload, record.timestamp(), record.headers()));
        metrics.alertGenerated(AlertType.VEHICLE_STOPPED);
        log.info(
                "stopped vehicle detected vehicle-id={} window={} movement-threshold={} alert-id={}",
                event.vehicleId(),
                detector.window(),
                detector.movementThresholdMeters(),
                alert.eventId());
    }
}

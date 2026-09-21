package com.coobi.logistics.streamprocessor.persistence;

import com.coobi.logistics.streamprocessor.event.AlertEvent;
import com.coobi.logistics.streamprocessor.processing.VehicleState;

/**
 * Persistence port of the derived state (MVP-4).
 *
 * <p>The processor writes what it derived, not what it consumed: the latest state of every
 * vehicle and the alerts it accepted. Telemetry itself is never persisted, so PostgreSQL
 * does not become an event store (MVP-4 goal).
 *
 * <p>The port exists so the Kafka topology can be driven with a recording implementation in
 * tests, and so the database can be replaced without touching the processing code. Both
 * operations must be idempotent, because Kafka Streams delivers at least once: a record
 * that is retried after a failure has to leave the database in the same state as the first
 * attempt would have.
 */
public interface TelemetryPersistence {

    /**
     * Creates the vehicle if it is new and overwrites its single latest-state row
     * (MVP-4.2). Repeated updates of one vehicle never append history.
     */
    void recordVehicleState(String vehicleId, VehicleState state);

    /**
     * Stores an alert once, keyed by its {@code eventId} (MVP-4.3). Persisting the same
     * alert again is a no-op rather than an error, so a replayed record cannot create a
     * duplicate row.
     */
    void recordAlert(AlertEvent alert);
}

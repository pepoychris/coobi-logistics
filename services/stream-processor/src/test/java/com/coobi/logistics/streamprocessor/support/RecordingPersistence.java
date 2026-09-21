package com.coobi.logistics.streamprocessor.support;

import com.coobi.logistics.streamprocessor.event.AlertEvent;
import com.coobi.logistics.streamprocessor.persistence.TelemetryPersistence;
import com.coobi.logistics.streamprocessor.processing.VehicleState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory stand-in for the persistence port, so the topology can be driven without a
 * database while the persistence calls themselves stay observable.
 *
 * <p>It mirrors the guarantees of the real implementation instead of just recording calls:
 * the latest state of a vehicle is one entry per {@code vehicleId}, and an alert is stored
 * once per {@code eventId}. A test that asserts on this recorder is therefore asserting on
 * the same idempotent contract the database enforces with its unique constraints.
 */
public final class RecordingPersistence implements TelemetryPersistence {

    private final Map<String, VehicleState> latestState = new LinkedHashMap<>();
    private final Map<String, AlertEvent> alertsByEventId = new LinkedHashMap<>();
    private final List<String> stateWrites = new ArrayList<>();

    @Override
    public void recordVehicleState(String vehicleId, VehicleState state) {
        latestState.put(vehicleId, state);
        stateWrites.add(vehicleId);
    }

    @Override
    public void recordAlert(AlertEvent alert) {
        alertsByEventId.put(alert.eventId().toString(), alert);
    }

    /** Latest state per vehicle, as the {@code vehicle_latest_state} table would hold it. */
    public Map<String, VehicleState> latestState() {
        return latestState;
    }

    /** One entry per distinct alert, as the unique {@code event_id} would leave it. */
    public List<AlertEvent> alerts() {
        return List.copyOf(alertsByEventId.values());
    }

    /** Every state write, in order, including the ones that overwrite a previous row. */
    public List<String> stateWrites() {
        return List.copyOf(stateWrites);
    }
}

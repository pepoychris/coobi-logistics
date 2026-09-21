package com.coobi.logistics.streamprocessor.processing;

import com.coobi.logistics.streamprocessor.event.VehicleLocationEvent;
import java.util.Objects;

/**
 * Result of inspecting one telemetry record: either the accepted event or the reason it
 * was rejected (MVP-2.2).
 *
 * <p>Exactly one of {@code event} and {@code error} is set, which the canonical
 * constructor enforces, so a downstream branch cannot silently treat a rejection as an
 * event. {@code originalEvent} carries the payload exactly as it was consumed, so the
 * rejection branch can build the dead letter envelope without re-reading the record.
 *
 * @param originalEvent payload exactly as consumed, or {@code null} when the record had none
 * @param event accepted event, or {@code null} when the record was rejected
 * @param error why the record was rejected, or {@code null} when it was accepted
 */
public record TelemetryInspection(String originalEvent, VehicleLocationEvent event, String error) {

    public TelemetryInspection {
        if ((event == null) == (error == null)) {
            throw new IllegalArgumentException("exactly one of event or error must be set");
        }
    }

    public static TelemetryInspection accepted(String originalEvent, VehicleLocationEvent event) {
        return new TelemetryInspection(originalEvent, Objects.requireNonNull(event, "event must not be null"), null);
    }

    public static TelemetryInspection rejected(String originalEvent, String error) {
        return new TelemetryInspection(originalEvent, null, Objects.requireNonNull(error, "error must not be null"));
    }

    public boolean isValid() {
        return event != null;
    }
}

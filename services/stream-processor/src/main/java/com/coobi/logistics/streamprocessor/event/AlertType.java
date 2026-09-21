package com.coobi.logistics.streamprocessor.event;

/**
 * Detection types supported by the alert contract (MVP-2.4).
 *
 * <p>Each type carries the {@code eventType} it is serialized as and its severity, so a
 * producer cannot publish an alert whose type, event type and severity disagree.
 *
 * <p>{@link #SPEEDING} is produced by the speed-limit detection (MVP-2.3) and
 * {@link #VEHICLE_STOPPED} by the stopped-vehicle detection (MVP-3.2).
 */
public enum AlertType {

    SPEEDING("SPEEDING_DETECTED", AlertSeverity.WARNING),
    VEHICLE_STOPPED("VEHICLE_STOPPED_DETECTED", AlertSeverity.WARNING);

    private final String eventType;
    private final AlertSeverity severity;

    AlertType(String eventType, AlertSeverity severity) {
        this.eventType = eventType;
        this.severity = severity;
    }

    /** Value of the {@code eventType} field of an alert of this type. */
    public String eventType() {
        return eventType;
    }

    /** Default severity of an alert of this type. */
    public AlertSeverity severity() {
        return severity;
    }

    /** Whether the given {@code eventType} value is one of the supported alert types. */
    public static boolean isSupportedEventType(String eventType) {
        for (AlertType type : values()) {
            if (type.eventType.equals(eventType)) {
                return true;
            }
        }
        return false;
    }
}

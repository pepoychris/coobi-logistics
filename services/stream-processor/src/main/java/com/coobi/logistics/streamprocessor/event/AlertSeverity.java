package com.coobi.logistics.streamprocessor.event;

/**
 * Severity of an alert (MVP-2.4).
 *
 * <p>MVP-2 only defines the warning level: every detection implemented so far describes a
 * condition an operator has to look at, not an outage of the platform itself.
 */
public enum AlertSeverity {
    WARNING
}

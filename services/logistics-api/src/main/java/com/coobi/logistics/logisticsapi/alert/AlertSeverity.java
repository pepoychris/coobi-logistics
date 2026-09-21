package com.coobi.logistics.logisticsapi.alert;

/**
 * Severity of an alert.
 *
 * <p>The contract has one severity today - the {@code alerts_severity_check} constraint of
 * the schema accepts exactly {@code WARNING} - and it is modelled as an enumeration rather
 * than as free text so a client can rely on the accepted values, and a request for any
 * other value is rejected instead of silently returning an empty page.
 */
public enum AlertSeverity {

    /** The alert is informative: it reports a threshold crossing, not a failure. */
    WARNING
}

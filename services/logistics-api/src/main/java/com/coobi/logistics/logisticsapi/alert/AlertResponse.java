package com.coobi.logistics.logisticsapi.alert;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * One alert, as {@code /api/v1/alerts} reports it.
 *
 * <p>{@code metadata} is embedded as JSON rather than as a string of JSON, so a client reads
 * the {@code AlertData} of the contract - the measured value and the threshold that produced
 * the alert - without parsing a second time.
 *
 * @param id surrogate key of the stored alert, unique within the database
 * @param eventId {@code eventId} of the alert contract
 * @param vehicleId vehicle the alert belongs to
 * @param type alert type of the contract
 * @param severity severity of the alert
 * @param occurredAt instant of the telemetry event that triggered the alert
 * @param createdAt instant the alert was stored
 * @param metadata {@code AlertData} of the contract, as stored
 */
@JsonPropertyOrder({
    "id",
    "eventId",
    "vehicleId",
    "type",
    "severity",
    "occurredAt",
    "createdAt",
    "metadata"
})
public record AlertResponse(
        Long id,
        UUID eventId,
        String vehicleId,
        AlertType type,
        AlertSeverity severity,
        Instant occurredAt,
        Instant createdAt,
        JsonNode metadata) {
}

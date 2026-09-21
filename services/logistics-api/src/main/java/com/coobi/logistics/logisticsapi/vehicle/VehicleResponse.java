package com.coobi.logistics.logisticsapi.vehicle;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;

/**
 * One vehicle of the fleet, as {@code /api/v1/vehicles} reports it.
 *
 * <p>The field names and units are the ones of the version-1 location contract the other
 * services share - {@code speed} in kilometres per hour, {@code heading} in degrees
 * clockwise from north - so a client reads the same names in the stream and in the API.
 * The two timestamps are kept apart on purpose: {@code lastUpdate} is when the vehicle was
 * last heard from, {@code updatedAt} is when this read model was last written.
 *
 * @param vehicleId business identifier of the vehicle
 * @param latitude degrees of the latest position, positive north
 * @param longitude degrees of the latest position, positive east
 * @param speed speed of the latest event, in kilometres per hour
 * @param heading heading of the latest event, in degrees clockwise from north
 * @param status movement status of the latest event
 * @param lastUpdate instant of the latest telemetry event
 * @param updatedAt instant the latest state was written
 */
@JsonPropertyOrder({
    "vehicleId",
    "latitude",
    "longitude",
    "speed",
    "heading",
    "status",
    "lastUpdate",
    "updatedAt"
})
public record VehicleResponse(
        String vehicleId,
        double latitude,
        double longitude,
        double speed,
        double heading,
        VehicleStatus status,
        Instant lastUpdate,
        Instant updatedAt) {
}

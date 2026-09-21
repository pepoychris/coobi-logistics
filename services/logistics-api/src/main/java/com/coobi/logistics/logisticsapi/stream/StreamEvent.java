package com.coobi.logistics.logisticsapi.stream;

import com.coobi.logistics.logisticsapi.alert.AlertResponse;
import com.coobi.logistics.logisticsapi.vehicle.VehicleResponse;

/**
 * One event of the browser event stream (MVP-6.1).
 *
 * <p>The payload is the response of the REST endpoint that owns the same object - an
 * {@link AlertResponse} or a {@link VehicleResponse} - and the type is the name of the SSE
 * event that carries it, so a browser reads the same field names, in the stream and in the
 * API, and dispatches on a name instead of on the shape of a document.
 *
 * @param type family of the event
 * @param payload the object as the REST endpoint of the same family reports it
 */
public record StreamEvent(Type type, Object payload) {

    /** The two families of event the stream carries, named as the SSE event names them. */
    public enum Type {
        /** An alert the stream processor stored: {@code event:alert}. */
        ALERT("alert"),
        /** The latest state of a vehicle that was just updated: {@code event:vehicle}. */
        VEHICLE("vehicle");

        private final String wireName;

        Type(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    /**
     * @param alert stored alert to stream
     * @return the alert as an event
     */
    public static StreamEvent alert(AlertResponse alert) {
        return new StreamEvent(Type.ALERT, alert);
    }

    /**
     * @param vehicle latest state of a vehicle
     * @return the vehicle as an event
     */
    public static StreamEvent vehicle(VehicleResponse vehicle) {
        return new StreamEvent(Type.VEHICLE, vehicle);
    }
}

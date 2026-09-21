package com.coobi.logistics.streamprocessor.processing;

import com.coobi.logistics.streamprocessor.event.VehicleLocationEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Explicit boundary validation of one telemetry record (MVP-2.1 and MVP-2.2).
 *
 * <p>Every rejection is a returned {@link TelemetryInspection} rather than a thrown
 * exception, so a single malformed record can never fail the stream or stop the records
 * behind it. The checks run from the most generic to the most specific, which keeps the
 * reported error actionable:
 *
 * <ol>
 *   <li>the payload is not blank and is a JSON object;
 *   <li>the schema version and the event type are the supported ones;
 *   <li>the payload can be deserialized into the version-1 event ... and it satisfies its
 *       Bean Validation constraints (coordinates, speed, heading, identifiers);
 *   <li>the Kafka key is the {@code vehicleId} the event declares (Global Rule 15).
 * </ol>
 */
public final class TelemetryInspector {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public TelemetryInspector(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    public TelemetryInspection inspect(String key, String payload) {
        if (payload == null || payload.isBlank()) {
            return TelemetryInspection.rejected(null, "payload must not be blank");
        }

        JsonNode document;
        try {
            document = objectMapper.readTree(payload);
        } catch (JsonProcessingException malformed) {
            return TelemetryInspection.rejected(payload, "malformed JSON payload: " + firstLine(rootMessage(malformed)));
        }
        if (document == null || !document.isObject()) {
            return TelemetryInspection.rejected(payload, "payload must be a JSON object");
        }

        String unsupported = unsupportedSchema(document);
        if (unsupported != null) {
            return TelemetryInspection.rejected(payload, unsupported);
        }

        VehicleLocationEvent event;
        try {
            event = objectMapper.treeToValue(document, VehicleLocationEvent.class);
        } catch (JsonProcessingException invalid) {
            return TelemetryInspection.rejected(payload, "invalid event payload: " + rootMessage(invalid));
        }

        Set<ConstraintViolation<VehicleLocationEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            return TelemetryInspection.rejected(payload, "invalid event: " + describe(violations));
        }

        String keyError = keyError(key, event);
        if (keyError != null) {
            return TelemetryInspection.rejected(payload, keyError);
        }

        return TelemetryInspection.accepted(payload, event);
    }

    private static String unsupportedSchema(JsonNode document) {
        JsonNode version = document.get("version");
        if (version == null || !version.canConvertToInt() || version.asInt() != VehicleLocationEvent.CURRENT_VERSION) {
            String reported = version == null ? "missing" : version.asText();
            return "unsupported event version: " + reported + " (expected " + VehicleLocationEvent.CURRENT_VERSION + ")";
        }
        JsonNode eventType = document.get("eventType");
        if (eventType == null
                || !VehicleLocationEvent.EVENT_TYPE_VEHICLE_LOCATION_UPDATED.equals(eventType.asText())) {
            String reported = eventType == null ? "missing" : eventType.asText();
            return "unsupported eventType: " + reported + " (expected "
                    + VehicleLocationEvent.EVENT_TYPE_VEHICLE_LOCATION_UPDATED + ")";
        }
        return null;
    }

    private static String keyError(String key, VehicleLocationEvent event) {
        if (key == null || key.isBlank()) {
            return "record key (vehicleId) must not be blank";
        }
        if (!key.equals(event.vehicleId())) {
            return "record key must match the event vehicleId: key=" + key + ", vehicleId=" + event.vehicleId();
        }
        return null;
    }

    private static String describe(Set<ConstraintViolation<VehicleLocationEvent>> violations) {
        return violations.stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .collect(Collectors.joining("; "));
    }

    /** The deepest cause carries the message the contract check actually produced. */
    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getMessage();
        }
        return message == null ? failure.getClass().getSimpleName() : message;
    }

    /** Jackson appends the source excerpt and the location on the lines after the first. */
    private static String firstLine(String message) {
        int lineBreak = message.indexOf('\n');
        return lineBreak < 0 ? message : message.substring(0, lineBreak);
    }
}

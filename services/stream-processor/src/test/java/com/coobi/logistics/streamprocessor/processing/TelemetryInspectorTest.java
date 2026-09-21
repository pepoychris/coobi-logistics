package com.coobi.logistics.streamprocessor.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.streamprocessor.support.TelemetryFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * MVP-2.1 and MVP-2.2: every rejection is reported as a value instead of an exception, so
 * a single invalid record cannot fail the stream.
 */
class TelemetryInspectorTest {

    private final ObjectMapper objectMapper = TelemetryFixtures.objectMapper();
    private final TelemetryInspector inspector =
            new TelemetryInspector(objectMapper, TelemetryFixtures.validator());

    @AfterAll
    static void closeValidator() {
        TelemetryFixtures.closeValidator();
    }

    @Test
    void acceptsAValidRecord() {
        String payload = TelemetryFixtures.payload("TRUCK-00001", 137.2);

        TelemetryInspection inspection = inspector.inspect("TRUCK-00001", payload);

        assertThat(inspection.isValid()).isTrue();
        assertThat(inspection.error()).isNull();
        assertThat(inspection.event().vehicleId()).isEqualTo("TRUCK-00001");
        assertThat(inspection.event().data().speed()).isEqualTo(137.2);
        assertThat(inspection.originalEvent()).isEqualTo(payload);
    }

    @Test
    void keepsThePayloadForTheDeadLetterEnvelope() {
        String payload = TelemetryFixtures.payload("TRUCK-00001", 80.0, 91.0, -0.3763);

        TelemetryInspection inspection = inspector.inspect("TRUCK-00001", payload);

        assertThat(inspection.isValid()).isFalse();
        assertThat(inspection.event()).isNull();
        assertThat(inspection.originalEvent()).isEqualTo(payload);
        assertThat(inspection.error()).isNotNull();
    }

    @Test
    void rejectsAMissingPayload() {
        assertThat(inspector.inspect("TRUCK-00001", null).error()).contains("payload must not be blank");
        assertThat(inspector.inspect("TRUCK-00001", "   ").error()).contains("payload must not be blank");
    }

    @Test
    void rejectsMalformedJson() {
        assertThat(inspector.inspect("TRUCK-00001", "{not json").error()).startsWith("malformed JSON payload");
    }

    @Test
    void rejectsAPayloadThatIsNotAJsonObject() {
        assertThat(inspector.inspect("TRUCK-00001", "[1,2,3]").error()).contains("must be a JSON object");
        assertThat(inspector.inspect("TRUCK-00001", "\"text\"").error()).contains("must be a JSON object");
        assertThat(inspector.inspect("TRUCK-00001", "null").error()).contains("must be a JSON object");
    }

    @Test
    void rejectsAnUnsupportedSchemaVersion() {
        assertThat(inspector.inspect("TRUCK-00001", payloadWith("version", 2)).error())
                .contains("unsupported event version: 2");
        assertThat(inspector.inspect("TRUCK-00001", payloadWithout("version")).error())
                .contains("unsupported event version: missing");
    }

    @Test
    void rejectsAnUnsupportedEventType() {
        assertThat(inspector.inspect("TRUCK-00001", payloadWith("eventType", "VEHICLE_ENGINE_STARTED")).error())
                .contains("unsupported eventType: VEHICLE_ENGINE_STARTED");
    }

    @Test
    void rejectsCoordinatesOutsideTheContract() {
        assertThat(inspector.inspect("TRUCK-00001", TelemetryFixtures.payload("TRUCK-00001", 80.0, 90.1, -0.3763))
                        .error())
                .contains("data.latitude");
        assertThat(inspector.inspect("TRUCK-00001", TelemetryFixtures.payload("TRUCK-00001", 80.0, 39.4699, 180.1))
                        .error())
                .contains("data.longitude");
    }

    @Test
    void rejectsNegativeSpeedAndAHeadingOutsideTheContract() {
        assertThat(inspector.inspect("TRUCK-00001", TelemetryFixtures.payload("TRUCK-00001", -0.1)).error())
                .contains("data.speed");
        assertThat(inspector.inspect("TRUCK-00001", payloadWith("data", dataWith("heading", 360.0))).error())
                .contains("data.heading");
    }

    @Test
    void rejectsABlankVehicleId() {
        assertThat(inspector.inspect("TRUCK-00001", TelemetryFixtures.payload("  ", 80.0)).error())
                .contains("vehicleId");
    }

    @Test
    void rejectsAMissingVehicleIdOrPayloadBody() {
        assertThat(inspector.inspect("TRUCK-00001", payloadWithout("vehicleId")).error())
                .contains("vehicleId");
        assertThat(inspector.inspect("TRUCK-00001", payloadWithout("data")).error())
                .contains("data");
        assertThat(inspector.inspect("TRUCK-00001", payloadWithout("eventId")).error())
                .contains("eventId");
    }

    @Test
    void rejectsAKeyThatIsNotTheVehicleId() {
        assertThat(inspector.inspect("TRUCK-99999", TelemetryFixtures.payload("TRUCK-00001", 80.0)).error())
                .contains("record key must match the event vehicleId");
        assertThat(inspector.inspect("  ", TelemetryFixtures.payload("TRUCK-00001", 80.0)).error())
                .contains("record key (vehicleId) must not be blank");
    }

    @Test
    void rejectsAValueOfTheWrongType() {
        assertThat(inspector.inspect("TRUCK-00001", payloadWith("data", dataWith("speed", "fast"))).error())
                .contains("invalid event payload");
    }

    @Test
    void acceptsAPayloadWithUnknownFields() {
        Map<String, Object> data = new LinkedHashMap<>(dataWith("heading", 214.5));
        data.put("unexpected", "value");
        Map<String, Object> payload = documentedPayload();
        payload.put("data", data);
        payload.put("unexpected", "value");

        assertThat(inspector.inspect("TRUCK-00001", TelemetryFixtures.serialize(payload)).isValid())
                .isTrue();
    }

    private String payloadWith(String field, Object value) {
        Map<String, Object> payload = documentedPayload();
        payload.put(field, value);
        return TelemetryFixtures.serialize(payload);
    }

    private String payloadWithout(String field) {
        Map<String, Object> payload = documentedPayload();
        payload.remove(field);
        return TelemetryFixtures.serialize(payload);
    }

    private static Map<String, Object> dataWith(String field, Object value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("latitude", 39.4699);
        data.put("longitude", -0.3763);
        data.put("speed", 80.0);
        data.put("heading", 214.5);
        data.put(field, value);
        return data;
    }

    private static Map<String, Object> documentedPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("eventType", "VEHICLE_LOCATION_UPDATED");
        payload.put("version", 1);
        payload.put("vehicleId", "TRUCK-00001");
        payload.put("timestamp", TelemetryFixtures.TIMESTAMP.toString());
        payload.put("data", dataWith("heading", 214.5));
        return payload;
    }
}

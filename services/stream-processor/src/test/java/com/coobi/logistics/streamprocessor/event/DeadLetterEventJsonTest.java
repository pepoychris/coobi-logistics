package com.coobi.logistics.streamprocessor.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.coobi.logistics.streamprocessor.support.Immutability;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/** MVP-2.2: serialized shape and immutability of the dead letter envelope. */
@JsonTest
class DeadLetterEventJsonTest {

    private static final Instant FAILED_AT = Instant.parse("2026-09-21T09:15:00.500Z");
    private static final String SOURCE_TOPIC = "logistics.vehicle.location.v1";
    private static final String ORIGINAL_EVENT = """
            {"eventId":"6b9f6f5e-6f25-4e8f-8f5f-5c0f0d1a2b3c","eventType":"VEHICLE_LOCATION_UPDATED"}""";

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void serializesTheDocumentedContract() {
        DeadLetterEvent event =
                new DeadLetterEvent(ORIGINAL_EVENT, "malformed JSON payload: unexpected character", FAILED_AT, SOURCE_TOPIC);

        assertThat(write(event))
                .isEqualTo("{\"originalEvent\":\"{\\\"eventId\\\":\\\"6b9f6f5e-6f25-4e8f-8f5f-5c0f0d1a2b3c\\\","
                        + "\\\"eventType\\\":\\\"VEHICLE_LOCATION_UPDATED\\\"}\","
                        + "\"error\":\"malformed JSON payload: unexpected character\","
                        + "\"failedAt\":\"2026-09-21T09:15:00.500Z\","
                        + "\"sourceTopic\":\"logistics.vehicle.location.v1\"}");
    }

    @Test
    void keepsThePayloadExactlyAsItWasConsumed() throws Exception {
        String notJson = "{not json";

        String written = write(new DeadLetterEvent(notJson, "malformed JSON payload", FAILED_AT, SOURCE_TOPIC));
        DeadLetterEvent restored = objectMapper.readValue(written, DeadLetterEvent.class);

        assertThat(restored).isEqualTo(new DeadLetterEvent(notJson, "malformed JSON payload", FAILED_AT, SOURCE_TOPIC));
        assertThat(restored.originalEvent()).isEqualTo(notJson);
    }

    @Test
    void roundTripsARecordWithoutAPayload() throws Exception {
        DeadLetterEvent original = new DeadLetterEvent(null, "payload must not be blank", FAILED_AT, SOURCE_TOPIC);

        DeadLetterEvent restored = objectMapper.readValue(write(original), DeadLetterEvent.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void keepsTheDocumentedFieldOrder() throws Exception {
        String written = write(new DeadLetterEvent(ORIGINAL_EVENT, "invalid event", FAILED_AT, SOURCE_TOPIC));

        List<String> fields = new ArrayList<>();
        objectMapper.readTree(written).fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactly("originalEvent", "error", "failedAt", "sourceTopic");
    }

    @Test
    void rejectsNullRequiredComponents() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new DeadLetterEvent(ORIGINAL_EVENT, null, FAILED_AT, SOURCE_TOPIC))
                .withMessageContaining("error");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new DeadLetterEvent(ORIGINAL_EVENT, "invalid event", null, SOURCE_TOPIC))
                .withMessageContaining("failedAt");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new DeadLetterEvent(ORIGINAL_EVENT, "invalid event", FAILED_AT, null))
                .withMessageContaining("sourceTopic");
    }

    @Test
    void eventsAreImmutable() {
        assertThat(Immutability.isRecordWithFinalFields(DeadLetterEvent.class)).isTrue();
        assertThat(Immutability.setterLikeMethods(DeadLetterEvent.class)).isEmpty();
        assertThat(Immutability.componentTypesAreImmutable(
                        DeadLetterEvent.class, List.of(String.class, Instant.class)))
                .isTrue();
    }

    private String write(DeadLetterEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception failure) {
            throw new IllegalStateException("serialization failed", failure);
        }
    }
}

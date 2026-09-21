package com.coobi.logistics.streamprocessor.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.coobi.logistics.streamprocessor.support.Immutability;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/** MVP-2.4: serialized shape, versioning and immutability of the alert event. */
@JsonTest
class AlertEventJsonTest {

    private static final UUID EVENT_ID = UUID.fromString("0f3c1a5e-2b1f-4a1f-9d4e-8c2f5b6a7d10");
    private static final Instant TIMESTAMP = Instant.parse("2026-09-21T09:15:00.123Z");

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void serializesTheDocumentedContract() {
        AlertEvent alert = new AlertEvent(
                EVENT_ID,
                "SPEEDING_DETECTED",
                1,
                "TRUCK-00182",
                AlertSeverity.WARNING,
                TIMESTAMP,
                new AlertData(137.2, 120.0));

        assertThat(write(alert))
                .isEqualTo("""
                        {"eventId":"0f3c1a5e-2b1f-4a1f-9d4e-8c2f5b6a7d10","eventType":"SPEEDING_DETECTED",\
                        "version":1,"vehicleId":"TRUCK-00182","severity":"WARNING",\
                        "timestamp":"2026-09-21T09:15:00.123Z","data":{"speed":137.2,"threshold":120.0}}""");
    }

    @Test
    void roundTripsWithoutLosingInformation() throws Exception {
        AlertEvent original = AlertEvent.create(
                AlertType.SPEEDING, "TRUCK-00182", TIMESTAMP, new AlertData(137.2, 120.0));

        AlertEvent restored = objectMapper.readValue(write(original), AlertEvent.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void createsVersionOneAlertsOfTheSupportedTypes() {
        AlertEvent speeding = AlertEvent.create(
                AlertType.SPEEDING, "TRUCK-00182", TIMESTAMP, new AlertData(137.2, 120.0));
        AlertEvent stopped = AlertEvent.create(
                AlertType.VEHICLE_STOPPED, "TRUCK-00007", TIMESTAMP, new AlertData(0.0, 120.0));

        assertThat(speeding.eventType()).isEqualTo("SPEEDING_DETECTED");
        assertThat(stopped.eventType()).isEqualTo("VEHICLE_STOPPED_DETECTED");
        assertThat(List.of(speeding, stopped))
                .allSatisfy(alert -> assertThat(alert.version()).isEqualTo(1))
                .allSatisfy(alert -> assertThat(alert.severity()).isEqualTo(AlertSeverity.WARNING));
    }

    @Test
    void supportsExactlyTheSpeedingAndStoppedTypes() {
        assertThat(AlertType.values())
                .extracting(AlertType::eventType)
                .containsExactly("SPEEDING_DETECTED", "VEHICLE_STOPPED_DETECTED");
    }

    @Test
    void givesEveryAlertAUniqueEventId() {
        Set<UUID> identifiers = IntStream.range(0, 1000)
                .mapToObj(index -> AlertEvent.create(
                                AlertType.SPEEDING, "TRUCK-00182", TIMESTAMP, new AlertData(137.2, 120.0))
                        .eventId())
                .collect(Collectors.toSet());

        assertThat(identifiers).hasSize(1000);
    }

    @Test
    void alertsAreImmutable() {
        assertThat(Immutability.isRecordWithFinalFields(AlertEvent.class)).isTrue();
        assertThat(Immutability.isRecordWithFinalFields(AlertData.class)).isTrue();
        assertThat(Immutability.setterLikeMethods(AlertEvent.class)).isEmpty();
        assertThat(Immutability.setterLikeMethods(AlertData.class)).isEmpty();
        assertThat(Immutability.componentTypesAreImmutable(
                        AlertEvent.class,
                        List.of(UUID.class, String.class, AlertSeverity.class, Instant.class, AlertData.class)))
                .isTrue();
        assertThat(Immutability.componentTypesAreImmutable(AlertData.class, List.of())).isTrue();
    }

    @Test
    void rejectsAnUnsupportedEventTypeOrVersion() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new AlertEvent(
                        EVENT_ID, "GEOFENCE_ENTERED", 1, "TRUCK-00182", AlertSeverity.WARNING, TIMESTAMP, data()))
                .withMessageContaining("eventType");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new AlertEvent(
                        EVENT_ID, "SPEEDING_DETECTED", 2, "TRUCK-00182", AlertSeverity.WARNING, TIMESTAMP, data()))
                .withMessageContaining("version");
    }

    @Test
    void rejectsNullRequiredComponents() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new AlertEvent(
                        null, "SPEEDING_DETECTED", 1, "TRUCK-00182", AlertSeverity.WARNING, TIMESTAMP, data()))
                .withMessageContaining("eventId");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new AlertEvent(
                        EVENT_ID, "SPEEDING_DETECTED", 1, "TRUCK-00182", null, TIMESTAMP, data()))
                .withMessageContaining("severity");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new AlertEvent(
                        EVENT_ID, "SPEEDING_DETECTED", 1, "TRUCK-00182", AlertSeverity.WARNING, null, data()))
                .withMessageContaining("timestamp");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new AlertEvent(
                        EVENT_ID, "SPEEDING_DETECTED", 1, "TRUCK-00182", AlertSeverity.WARNING, TIMESTAMP, null))
                .withMessageContaining("data");
    }

    @Test
    void rejectsNonFiniteAlertData() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new AlertData(Double.NaN, 120.0))
                .withMessageContaining("speed");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new AlertData(137.2, 0.0))
                .withMessageContaining("threshold");
    }

    private String write(AlertEvent alert) {
        try {
            return objectMapper.writeValueAsString(alert);
        } catch (Exception failure) {
            throw new IllegalStateException("serialization failed", failure);
        }
    }

    private static AlertData data() {
        return new AlertData(137.2, 120.0);
    }
}

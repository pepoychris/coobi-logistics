package com.coobi.logistics.eventgenerator.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/** MVP-1.2: serialized shape and immutability of the location event. */
@JsonTest
class VehicleLocationEventJsonTest {

    private static final UUID EVENT_ID = UUID.fromString("6b9f6f5e-6f25-4e8f-8f5f-5c0f0d1a2b3c");
    private static final Instant TIMESTAMP = Instant.parse("2026-09-21T09:15:00.123Z");

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void serializesTheDocumentedContract() {
        VehicleLocationEvent event = new VehicleLocationEvent(
                EVENT_ID,
                "VEHICLE_LOCATION_UPDATED",
                1,
                "TRUCK-00001",
                TIMESTAMP,
                new VehicleLocationData(39.4699, -0.3763, 82.3, 214.5));

        String json = write(event);

        assertThat(json).isEqualTo("""
                {"eventId":"6b9f6f5e-6f25-4e8f-8f5f-5c0f0d1a2b3c","eventType":"VEHICLE_LOCATION_UPDATED",\
                "version":1,"vehicleId":"TRUCK-00001","timestamp":"2026-09-21T09:15:00.123Z",\
                "data":{"latitude":39.4699,"longitude":-0.3763,"speed":82.3,"heading":214.5}}""");
    }

    @Test
    void writesTheTimestampAsAnIsoUtcInstant() {
        String timestamp = readField(write(VehicleLocationEvent.create("TRUCK-00007", TIMESTAMP, data())), "timestamp");

        assertThat(timestamp).matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");
    }

    @Test
    void roundTripsWithoutLosingInformation() throws Exception {
        VehicleLocationEvent original = VehicleLocationEvent.create("TRUCK-00042", TIMESTAMP, data());

        VehicleLocationEvent restored = objectMapper.readValue(write(original), VehicleLocationEvent.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void eventAndPayloadAreImmutable() {
        assertThat(VehicleLocationEvent.class.isRecord()).isTrue();
        assertThat(VehicleLocationData.class.isRecord()).isTrue();
        assertThat(allFieldsAreFinal(VehicleLocationEvent.class)).isTrue();
        assertThat(allFieldsAreFinal(VehicleLocationData.class)).isTrue();
        assertThat(setterLikeMethods(VehicleLocationEvent.class)).isEmpty();
        assertThat(componentTypesAreImmutable()).isTrue();
    }

    private String write(VehicleLocationEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception failure) {
            throw new IllegalStateException("serialization failed", failure);
        }
    }

    private String readField(String json, String field) {
        try {
            return objectMapper.readTree(json).get(field).asText();
        } catch (Exception failure) {
            throw new IllegalStateException("reading " + field + " failed", failure);
        }
    }

    private static VehicleLocationData data() {
        return new VehicleLocationData(39.4699, -0.3763, 82.3, 214.5);
    }

    private static boolean allFieldsAreFinal(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).allMatch(field -> Modifier.isFinal(field.getModifiers()));
    }

    private static List<String> setterLikeMethods(Class<?> type) {
        return Arrays.stream(type.getMethods())
                .filter(method -> method.getName().startsWith("set") && method.getParameterCount() == 1)
                .map(Method::getName)
                .toList();
    }

    private static boolean componentTypesAreImmutable() {
        List<Class<?>> immutableTypes = List.of(UUID.class, String.class, Instant.class, VehicleLocationData.class);
        for (Field field : VehicleLocationEvent.class.getDeclaredFields()) {
            if (!field.getType().isPrimitive() && !immutableTypes.contains(field.getType())) {
                return false;
            }
        }
        return true;
    }
}

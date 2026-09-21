package com.coobi.logistics.logisticsapi.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The stored metadata is handed to the client as JSON, and a row that cannot be read does not
 * fail the page it appears in.
 */
class AlertMetadataTest {

    private final AlertMetadata metadata = new AlertMetadata(new ObjectMapper());

    @Test
    void readsTheStoredAlertData() {
        JsonNode data = metadata.read(1L, "{\"speed\":131.5,\"threshold\":120.0}");

        assertThat(data.path("speed").asDouble()).isEqualTo(131.5);
        assertThat(data.path("threshold").asDouble()).isEqualTo(120.0);
    }

    @Test
    void reportsAnUnreadableRowAsTheTextItHolds() {
        JsonNode data = metadata.read(7L, "not json at all");

        assertThat(data.isTextual()).isTrue();
        assertThat(data.asText()).isEqualTo("not json at all");
    }
}

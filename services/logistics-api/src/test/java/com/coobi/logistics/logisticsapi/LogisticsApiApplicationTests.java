package com.coobi.logistics.logisticsapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.logisticsapi.statistics.StatisticsResponse;
import com.coobi.logistics.logisticsapi.web.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The service as it starts: healthy, serving on its own port, and answering the operator
 * views against a database it reads and does not write.
 *
 * <p>The suite runs the real context with the metrics source of the stream processor pointing
 * at a closed port, which is the state of a developer machine that runs only the API: the
 * statistics endpoint has to stay available and report the values it cannot read as absent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LogisticsApiApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void reportsItselfHealthy() {
        ResponseEntity<JsonNode> health = rest.getForEntity("/actuator/health", JsonNode.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody().path("status").asText()).isEqualTo("UP");
    }

    @Test
    void answersAnEmptyFleetAndAnEmptyAlertPage() {
        ResponseEntity<PageResponse<JsonNode>> vehicles = rest.exchange(
                "/api/v1/vehicles",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {
                });
        ResponseEntity<PageResponse<JsonNode>> alerts = rest.exchange(
                "/api/v1/alerts",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {
                });

        assertThat(vehicles.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(vehicles.getBody().content()).isEmpty();
        assertThat(vehicles.getBody().totalElements()).isZero();
        assertThat(alerts.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(alerts.getBody().content()).isEmpty();
    }

    @Test
    void answersLiveStatisticsWhileTheProcessorIsUnreachable() {
        ResponseEntity<StatisticsResponse> statistics =
                rest.getForEntity("/api/v1/statistics", StatisticsResponse.class);

        assertThat(statistics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getBody().activeVehicles()).isZero();
        assertThat(statistics.getBody().alertsGenerated()).isZero();
        assertThat(statistics.getBody().uptimeSeconds()).isGreaterThanOrEqualTo(0L);
        assertThat(statistics.getBody().processedEvents())
                .as("a counter that cannot be read is reported as absent, never as a number")
                .isNull();
        assertThat(statistics.getBody().eventsPerSecond()).isNull();
    }

    @Test
    void answersNotFoundForAVehicleThatWasNeverSeen() {
        ResponseEntity<JsonNode> missing = rest.getForEntity("/api/v1/vehicles/TRUCK-00042", JsonNode.class);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().path("title").asText()).isEqualTo("Not Found");
    }

}

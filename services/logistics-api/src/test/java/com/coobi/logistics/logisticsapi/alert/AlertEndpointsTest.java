package com.coobi.logistics.logisticsapi.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coobi.logistics.logisticsapi.web.ApiNotFoundException;
import com.coobi.logistics.logisticsapi.web.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-5.3 over HTTP: the three filters, paging, the rejection of invalid filter values and
 * the {@code 404} of an unknown alert.
 *
 * <p>The service is replaced by a test double, so these tests are about the contract of the
 * endpoint; the filters themselves are verified against a real database in
 * {@link com.coobi.logistics.logisticsapi.persistence.AlertReadModelTest}.
 */
@WebMvcTest(controllers = AlertController.class)
class AlertEndpointsTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-21T09:15:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AlertQueryService alerts;

    @Test
    void answersOnePageOfAlerts() throws Exception {
        given(alerts.page(isNull(), isNull(), isNull(), eq(0), eq(20)))
                .willReturn(new PageResponse<>(List.of(alert()), 0, 20, 1L, 1, true, true));

        mvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.content[0].eventId").value("2f8d3c1e-0b52-4a4f-9d76-8e13a3f0c111"))
                .andExpect(jsonPath("$.content[0].vehicleId").value("TRUCK-00001"))
                .andExpect(jsonPath("$.content[0].type").value("SPEEDING_DETECTED"))
                .andExpect(jsonPath("$.content[0].severity").value("WARNING"))
                .andExpect(jsonPath("$.content[0].occurredAt").value("2026-09-21T09:15:00Z"))
                .andExpect(jsonPath("$.content[0].metadata.speed").value(131.5))
                .andExpect(jsonPath("$.content[0].metadata.threshold").value(120.0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void answersTheAlertContractAndNoStorageColumn() throws Exception {
        given(alerts.page(isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .willReturn(new PageResponse<>(List.of(alert()), 0, 20, 1L, 1, true, true));

        String body = mvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode stored = new ObjectMapper().readTree(body).path("content").path(0);
        assertThat(stored.fieldNames()).toIterable().containsExactly(
                "id",
                "eventId",
                "vehicleId",
                "type",
                "severity",
                "occurredAt",
                "createdAt",
                "metadata");
    }

    @Test
    void forwardsEveryFilterIndependently() throws Exception {
        given(alerts.page(any(), any(), any(), eq(0), eq(20)))
                .willReturn(PageResponse.of(Page.empty()));

        mvc.perform(get("/api/v1/alerts").param("vehicleId", "TRUCK-00001")).andExpect(status().isOk());
        verify(alerts).page("TRUCK-00001", null, null, 0, 20);

        mvc.perform(get("/api/v1/alerts").param("type", "VEHICLE_STOPPED_DETECTED"))
                .andExpect(status().isOk());
        verify(alerts).page(null, AlertType.VEHICLE_STOPPED_DETECTED, null, 0, 20);

        mvc.perform(get("/api/v1/alerts").param("severity", "WARNING")).andExpect(status().isOk());
        verify(alerts).page(null, null, AlertSeverity.WARNING, 0, 20);

        mvc.perform(get("/api/v1/alerts")
                        .param("vehicleId", "TRUCK-00001")
                        .param("type", "SPEEDING_DETECTED")
                        .param("severity", "WARNING"))
                .andExpect(status().isOk());
        verify(alerts).page("TRUCK-00001", AlertType.SPEEDING_DETECTED, AlertSeverity.WARNING, 0, 20);
    }

    @Test
    void forwardsThePagingParameters() throws Exception {
        given(alerts.page(isNull(), isNull(), isNull(), eq(3), eq(5)))
                .willReturn(PageResponse.of(Page.empty()));

        mvc.perform(get("/api/v1/alerts").param("page", "3").param("size", "5")).andExpect(status().isOk());

        verify(alerts).page(null, null, null, 3, 5);
    }

    @Test
    void rejectsATypeThatIsNotPartOfTheContract() throws Exception {
        mvc.perform(get("/api/v1/alerts").param("type", "HARD_BRAKING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail", containsString("HARD_BRAKING")))
                .andExpect(jsonPath("$.detail", containsString("SPEEDING_DETECTED")))
                .andExpect(jsonPath("$.detail", containsString("VEHICLE_STOPPED_DETECTED")));
    }

    @Test
    void rejectsASeverityThatIsNotPartOfTheContract() throws Exception {
        mvc.perform(get("/api/v1/alerts").param("severity", "CRITICAL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("CRITICAL")))
                .andExpect(jsonPath("$.detail", containsString("WARNING")));
    }

    @Test
    void rejectsPagingOutsideTheDocumentedRange() throws Exception {
        mvc.perform(get("/api/v1/alerts").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("greater than or equal to 0")));

        mvc.perform(get("/api/v1/alerts").param("size", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("less than or equal to 100")));
    }

    @Test
    void answersNotFoundForAnUnknownAlert() throws Exception {
        given(alerts.byId(404L)).willThrow(new ApiNotFoundException("no alert with id 404"));

        mvc.perform(get("/api/v1/alerts/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("no alert with id 404"));
    }

    @Test
    void rejectsAnAlertIdThatIsNotANumber() throws Exception {
        mvc.perform(get("/api/v1/alerts/latest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail", containsString("latest")));
    }

    private static AlertResponse alert() {
        return new AlertResponse(
                7L,
                UUID.fromString("2f8d3c1e-0b52-4a4f-9d76-8e13a3f0c111"),
                "TRUCK-00001",
                AlertType.SPEEDING_DETECTED,
                AlertSeverity.WARNING,
                OCCURRED_AT,
                OCCURRED_AT.plusSeconds(1),
                new ObjectMapper().createObjectNode().put("speed", 131.5).put("threshold", 120.0));
    }
}

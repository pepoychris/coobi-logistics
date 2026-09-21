package com.coobi.logistics.logisticsapi.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-5.2 over HTTP: paging, the status filter, the rejection of invalid parameters and the
 * {@code 404} of an unknown vehicle.
 *
 * <p>The service is replaced by a test double, so these tests are about the contract of the
 * endpoint - the query parameters, the status codes and the shape of the body - and not about
 * the database; the read model itself is verified against a real database in
 * {@link com.coobi.logistics.logisticsapi.persistence.VehicleReadModelTest}.
 */
@WebMvcTest(controllers = VehicleController.class)
class VehicleEndpointsTest {

    private static final Instant LAST_UPDATE = Instant.parse("2026-09-21T09:15:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private VehicleQueryService vehicles;

    @Test
    void answersOnePageOfTheFleet() throws Exception {
        given(vehicles.page(isNull(), eq(0), eq(20)))
                .willReturn(new PageResponse<>(List.of(truck()), 0, 20, 1L, 1, true, true));

        mvc.perform(get("/api/v1/vehicles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].vehicleId").value("TRUCK-00001"))
                .andExpect(jsonPath("$.content[0].latitude").value(39.4699))
                .andExpect(jsonPath("$.content[0].longitude").value(-0.3763))
                .andExpect(jsonPath("$.content[0].speed").value(80.0))
                .andExpect(jsonPath("$.content[0].heading").value(214.5))
                .andExpect(jsonPath("$.content[0].status").value("MOVING"))
                .andExpect(jsonPath("$.content[0].lastUpdate").value("2026-09-21T09:15:00Z"))
                .andExpect(jsonPath("$.content[0].updatedAt").value("2026-09-21T09:15:01Z"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void answersTheVehicleContractAndNoStorageColumn() throws Exception {
        given(vehicles.page(isNull(), anyInt(), anyInt()))
                .willReturn(new PageResponse<>(List.of(truck()), 0, 20, 1L, 1, true, true));

        String body = mvc.perform(get("/api/v1/vehicles"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // The read model is mapped to a DTO, so the response carries the eight fields of the
        // contract - and nothing that belongs to the table behind it.
        JsonNode vehicle = new ObjectMapper().readTree(body).path("content").path(0);
        assertThat(vehicle.fieldNames()).toIterable().containsExactly(
                "vehicleId",
                "latitude",
                "longitude",
                "speed",
                "heading",
                "status",
                "lastUpdate",
                "updatedAt");
    }

    @Test
    void forwardsThePagingParameters() throws Exception {
        given(vehicles.page(isNull(), eq(2), eq(50))).willReturn(PageResponse.of(Page.empty()));

        mvc.perform(get("/api/v1/vehicles").param("page", "2").param("size", "50"))
                .andExpect(status().isOk());

        verify(vehicles).page(null, 2, 50);
    }

    @Test
    void filtersByStatus() throws Exception {
        given(vehicles.page(eq(VehicleStatus.STOPPED), eq(0), eq(20)))
                .willReturn(PageResponse.of(Page.empty()));

        mvc.perform(get("/api/v1/vehicles").param("status", "STOPPED")).andExpect(status().isOk());

        verify(vehicles).page(VehicleStatus.STOPPED, 0, 20);
    }

    @Test
    void acceptsAnEmptyStatusAsNoFilter() throws Exception {
        given(vehicles.page(isNull(), eq(0), eq(20))).willReturn(PageResponse.of(Page.empty()));

        // An empty query parameter means "not asked for", so the fleet is answered unfiltered
        // instead of rejected.
        mvc.perform(get("/api/v1/vehicles").param("status", "")).andExpect(status().isOk());

        verify(vehicles).page(null, 0, 20);
    }

    @Test
    void rejectsAFilterValueWhoseCaseDoesNotMatch() throws Exception {
        // The filter values are the contract values, so they are written the way the alert and
        // status enumerations are, and anything else names what the endpoint accepts.
        mvc.perform(get("/api/v1/vehicles").param("status", "moving"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("MOVING")));
    }

    @Test
    void rejectsAStatusThatIsNotPartOfTheContract() throws Exception {
        mvc.perform(get("/api/v1/vehicles").param("status", "FLYING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", containsString("FLYING")))
                .andExpect(jsonPath("$.detail", containsString("MOVING")))
                .andExpect(jsonPath("$.detail", containsString("STOPPED")));
    }

    @Test
    void rejectsANegativePage() throws Exception {
        mvc.perform(get("/api/v1/vehicles").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("greater than or equal to 0")));
    }

    @Test
    void rejectsASizeOutsideTheDocumentedRange() throws Exception {
        mvc.perform(get("/api/v1/vehicles").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("greater than or equal to 1")));

        mvc.perform(get("/api/v1/vehicles").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("less than or equal to 100")));
    }

    @Test
    void rejectsAPageThatIsNotANumber() throws Exception {
        mvc.perform(get("/api/v1/vehicles").param("page", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"));
    }

    @Test
    void answersNotFoundForAVehicleThatWasNeverSeen() throws Exception {
        given(vehicles.byId("TRUCK-00042"))
                .willThrow(new ApiNotFoundException("no vehicle with id 'TRUCK-00042'"));

        mvc.perform(get("/api/v1/vehicles/TRUCK-00042"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("no vehicle with id 'TRUCK-00042'"));
    }

    private static VehicleResponse truck() {
        return new VehicleResponse(
                "TRUCK-00001",
                39.4699,
                -0.3763,
                80.0,
                214.5,
                VehicleStatus.MOVING,
                LAST_UPDATE,
                LAST_UPDATE.plusSeconds(1));
    }
}

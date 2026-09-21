package com.coobi.logistics.logisticsapi.statistics;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-5.4 over HTTP: the shape of the statistics response and the fact that a value whose
 * source is unavailable is reported as {@code null} instead of being replaced by a number.
 */
@WebMvcTest(controllers = StatisticsController.class)
class StatisticsEndpointsTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private StatisticsService statistics;

    @Test
    void answersTheStatisticsContract() throws Exception {
        given(statistics.current()).willReturn(new StatisticsResponse(12_938_281L, 12_492L, 5_000L, 281L, 8_271L));

        mvc.perform(get("/api/v1/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedEvents").value(12_938_281L))
                .andExpect(jsonPath("$.eventsPerSecond").value(12_492L))
                .andExpect(jsonPath("$.activeVehicles").value(5_000L))
                .andExpect(jsonPath("$.alertsGenerated").value(281L))
                .andExpect(jsonPath("$.uptimeSeconds").value(8_271L));
    }

    @Test
    void distinguishesAnUnavailableSourceFromZero() throws Exception {
        given(statistics.current()).willReturn(new StatisticsResponse(null, null, 0L, 0L, 12L));

        mvc.perform(get("/api/v1/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedEvents").value(nullValue()))
                .andExpect(jsonPath("$.eventsPerSecond").value(nullValue()))
                .andExpect(jsonPath("$.activeVehicles").value(0))
                .andExpect(jsonPath("$.alertsGenerated").value(0))
                .andExpect(jsonPath("$.uptimeSeconds").value(12));
    }
}

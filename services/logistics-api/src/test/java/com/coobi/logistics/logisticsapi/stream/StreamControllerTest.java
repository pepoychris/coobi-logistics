package com.coobi.logistics.logisticsapi.stream;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * MVP-6 over HTTP: both endpoints answer with a Server-Sent Events response that stays open,
 * and a stream that already serves its maximum of browsers says so instead of holding a
 * connection it cannot serve.
 */
@WebMvcTest(controllers = StreamController.class)
class StreamControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EventStreamService events;

    @MockitoBean
    private StatisticsStreamService statistics;

    @Test
    void establishesTheEventStream() throws Exception {
        given(events.subscribe()).willReturn(new SseEmitter());

        // The response is opened and stays open. What the response says on the wire - the
        // text/event-stream content type and the frames - is asserted over a real connection in
        // StreamingEndpointsTest, because a slice test never commits a response.
        mvc.perform(get("/api/v1/stream/events"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    void establishesTheStatisticsStream() throws Exception {
        given(statistics.subscribe()).willReturn(new SseEmitter());

        mvc.perform(get("/api/v1/stream/statistics"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    void refusesAConnectionAboveTheConfiguredMaximum() throws Exception {
        given(events.subscribe()).willThrow(new StreamCapacityExceededException("events", 32));

        mvc.perform(get("/api/v1/stream/events"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail", containsString("events")))
                .andExpect(jsonPath("$.detail", containsString("32")))
                .andExpect(jsonPath("$.instance").value("/api/v1/stream/events"));
    }
}

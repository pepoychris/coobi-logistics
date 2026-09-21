package com.coobi.logistics.logisticsapi.stream;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/v1/stream/events} and {@code GET /api/v1/stream/statistics} (MVP-6).
 *
 * <p>Both endpoints answer with Server-Sent Events: the response is sent as
 * {@code text/event-stream}, it stays open, and it carries events as they happen. The frames,
 * their names and the settings that bound them are documented in docs/logistics-api.md.
 *
 * <p>Nothing about a connection is decided here: the controller names the endpoint and hands
 * the request to the service that holds its connections, which keeps a stream testable without
 * a clock and keeps the controller as thin as the REST one next to it.
 */
@RestController
@RequestMapping(path = "/api/v1/stream")
public class StreamController {

    private final EventStreamService events;
    private final StatisticsStreamService statistics;

    public StreamController(EventStreamService events, StatisticsStreamService statistics) {
        this.events = events;
        this.statistics = statistics;
    }

    /**
     * Opens the sampled event stream.
     *
     * <p>Carries {@code alert} events for the alerts the processor stores and {@code vehicle}
     * events for the vehicles whose state the processor updates, sampled so that a browser
     * never receives the throughput of the pipeline.
     *
     * @return the open connection
     */
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return events.subscribe();
    }

    /**
     * Opens the live statistics stream.
     *
     * <p>Carries {@code statistics} events with the same fields as {@code /api/v1/statistics},
     * sent at the configured interval.
     *
     * @return the open connection
     */
    @GetMapping(path = "/statistics", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter statistics() {
        return statistics.subscribe();
    }
}

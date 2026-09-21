package com.coobi.logistics.logisticsapi.stream;

import com.coobi.logistics.logisticsapi.config.StreamProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * {@code GET /api/v1/stream/events} (MVP-6.1).
 *
 * <p>The endpoint samples the read model into a bounded stream: every tick reads what the
 * pipeline stored since the previous one and sends at most the configured number of alerts and
 * vehicle states, oldest first. The volume a browser receives is therefore set by this
 * service's configuration and not by the load of the pipeline - a broker that processed
 * thousands of records in the same second still produces one tick's worth of events - and the
 * events a browser does receive are the ones that happened most recently.
 */
@Service
public class EventStreamService extends SseEndpoint {

    /** Name of the stream, in its logs, its metrics and its event names' family. */
    public static final String STREAM = "events";

    private final StreamEventFeed feed;
    private final int maxAlertsPerPoll;
    private final int maxVehiclesPerPoll;

    @Autowired
    public EventStreamService(
            StreamProperties properties,
            @Qualifier("streamScheduler") TaskScheduler scheduler,
            ObjectMapper json,
            MeterRegistry meters,
            StreamEventFeed feed) {
        this(properties, scheduler, new SseStream(STREAM, properties.client(), json, meters), feed);
    }

    /**
     * The same endpoint over the connections supplied by the caller, which is how a test
     * drives it without a browser and without a clock.
     */
    EventStreamService(
            StreamProperties properties,
            TaskScheduler scheduler,
            SseStream stream,
            StreamEventFeed feed) {
        super(STREAM, properties.events().pollInterval(), scheduler, stream);
        this.maxAlertsPerPoll = properties.events().maxAlertsPerPoll();
        this.maxVehiclesPerPoll = properties.events().maxVehiclesPerPoll();
        this.feed = Objects.requireNonNull(feed, "feed must not be null");
    }

    @Override
    protected void connectionOpened() {
        // The stream carries what happens while it is open: the history is a page of
        // /api/v1/alerts or /api/v1/vehicles, and it is fetched, not streamed.
        feed.start();
    }

    @Override
    protected void publish() {
        feed.poll(maxAlertsPerPoll, maxVehiclesPerPoll)
                .forEach(event -> stream().publish(event.type().wireName(), event.payload()));
    }
}

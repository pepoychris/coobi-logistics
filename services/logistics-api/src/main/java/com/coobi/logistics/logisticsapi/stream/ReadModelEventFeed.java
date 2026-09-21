package com.coobi.logistics.logisticsapi.stream;

import com.coobi.logistics.logisticsapi.alert.AlertQueryService;
import com.coobi.logistics.logisticsapi.alert.AlertResponse;
import com.coobi.logistics.logisticsapi.vehicle.VehicleQueryService;
import com.coobi.logistics.logisticsapi.vehicle.VehicleResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The events of the read model, as the browser event stream sees them (MVP-6.1).
 *
 * <p>The pipeline has no browser-facing publisher, and this service deliberately does not
 * subscribe to Kafka - forwarding a topic to a browser is exactly what MVP-6 forbids - so the
 * events a browser may see are the ones the processor has already stored: the alerts it
 * accepted and the vehicle states it overwrote. Both are read here with a cursor, so a tick
 * carries what was stored since the previous one and nothing is read twice.
 *
 * <p>A cursor is the position of the newest recorded event, never a wall clock: the alerts are
 * keyed by their surrogate id and the vehicles by the event time of their telemetry, and both
 * are ordered in the database. When a cursor moves, it moves to the newest record of the batch,
 * so a burst that does not fit in one tick is sampled - the newest events are the ones sent,
 * and the rest are skipped rather than queued - which is what keeps a browser at the live edge
 * instead of showing it an hour of history one tick at a time. An id is unique, so every alert
 * is either sent or deliberately skipped; two vehicles whose telemetry carries exactly the same
 * event time are one position, so one of them may be left to the REST view, which is what a
 * sample of an ordered column costs (see docs/logistics-api.md).
 *
 * <p>A vehicle state that is overwritten twice between two ticks is one event, not two: the
 * read model keeps one row per vehicle by design (MVP-4), and the stream reports what the state
 * is now rather than what it was.
 */
@Component
@Transactional(readOnly = true)
public class ReadModelEventFeed implements StreamEventFeed {

    private static final Logger log = LoggerFactory.getLogger(ReadModelEventFeed.class);

    private final AlertQueryService alerts;
    private final VehicleQueryService vehicles;

    /** Id of the newest alert already handed out; {@code 0} means "nothing yet". */
    private long alertCursor;

    /** Event time of the newest vehicle state already handed out. */
    private Instant vehicleCursor = Instant.EPOCH;

    private boolean started;

    public ReadModelEventFeed(AlertQueryService alerts, VehicleQueryService vehicles) {
        this.alerts = Objects.requireNonNull(alerts, "alerts must not be null");
        this.vehicles = Objects.requireNonNull(vehicles, "vehicles must not be null");
    }

    @Override
    public synchronized void start() {
        alertCursor = alerts.liveEdge();
        vehicleCursor = vehicles.liveEdge().orElse(Instant.EPOCH);
        started = true;
        log.debug("stream events: positioned at the live edge alert={} vehicle={}", alertCursor, vehicleCursor);
    }

    @Override
    public synchronized List<StreamEvent> poll(int maxAlerts, int maxVehicles) {
        if (!started) {
            // Nothing may be streamed before a browser has placed the feed at the live edge:
            // a tick that ran first would replay the history the REST endpoints already serve.
            return List.of();
        }
        List<StreamEvent> events = new ArrayList<>(maxAlerts + maxVehicles);
        events.addAll(newAlerts(maxAlerts));
        events.addAll(newVehicles(maxVehicles));
        return events;
    }

    private List<StreamEvent> newAlerts(int max) {
        List<AlertResponse> found = alerts.newerThan(alertCursor, max + 1);
        if (found.isEmpty()) {
            return List.of();
        }
        // The batch is ordered newest first, so the first element is the newest alert that
        // exists: the cursor moves there even when the batch does not fit, which is what keeps
        // the stream live under a burst.
        alertCursor = found.get(0).id();
        if (found.size() > max) {
            found = found.subList(0, max);
            log.debug("stream events: sampled {} alerts, the older ones of the burst were skipped", max);
        }
        return oldestFirst(found, StreamEvent::alert);
    }

    private List<StreamEvent> newVehicles(int max) {
        List<VehicleResponse> found = vehicles.newerThan(vehicleCursor, max + 1);
        if (found.isEmpty()) {
            return List.of();
        }
        vehicleCursor = found.get(0).lastUpdate();
        if (found.size() > max) {
            found = found.subList(0, max);
            log.debug("stream events: sampled {} vehicle states, the older ones of the burst were skipped", max);
        }
        return oldestFirst(found, StreamEvent::vehicle);
    }

    /**
     * Turns a batch that arrived newest first into the order a client reads it in, so a
     * dashboard appends events instead of inserting them at the front.
     */
    private static <T> List<StreamEvent> oldestFirst(List<T> newestFirst, Function<T, StreamEvent> toEvent) {
        List<StreamEvent> events = new ArrayList<>(newestFirst.size());
        for (int index = newestFirst.size() - 1; index >= 0; index--) {
            events.add(toEvent.apply(newestFirst.get(index)));
        }
        return events;
    }
}

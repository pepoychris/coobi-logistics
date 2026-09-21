package com.coobi.logistics.logisticsapi.stream;

import java.util.List;

/**
 * The source of the events the browser event stream samples (MVP-6.1).
 *
 * <p>The interface exists so the endpoint can be verified with a feed that produces exactly
 * what a test needs, and so the endpoint does not depend on where the events come from. The
 * feed of this service reads the read model (there is no browser-facing publisher in the
 * pipeline, and the API deliberately does not subscribe to Kafka: forwarding a topic to a
 * browser is the one thing MVP-6 forbids).
 *
 * <p>Both methods are called from the endpoint, never from a request thread.
 */
public interface StreamEventFeed {

    /**
     * Places the feed at the live edge.
     *
     * <p>Called when a browser connects to a stream that had no connection: the feed starts
     * from the newest record that exists instead of replaying the history, which the paged
     * REST endpoints already serve. A stream therefore carries what happens while it is open.
     */
    void start();

    /**
     * Reads what happened since the previous call.
     *
     * @param maxAlerts most alerts to answer with
     * @param maxVehicles most vehicle states to answer with
     * @return the events, oldest first; fewer than the maxima when less happened, and never
     *         more than one of them, so one tick of the stream is bounded by construction
     */
    List<StreamEvent> poll(int maxAlerts, int maxVehicles);
}

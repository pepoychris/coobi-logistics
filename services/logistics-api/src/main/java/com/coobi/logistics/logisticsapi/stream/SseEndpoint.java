package com.coobi.logistics.logisticsapi.stream;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One SSE endpoint of the API: the browsers connected to it and the ticker that feeds them
 * (MVP-6.1 and MVP-6.2).
 *
 * <p>The endpoint does no work while no browser is connected. Its ticker is created with the
 * first connection of a quiet period and cancelled with the last one, so an API nobody watches
 * costs an empty map and the sources of the data are not read at all - which is what the
 * milestone asks for: a stream is a view of the pipeline, and a view nobody looks at must not
 * touch it. The interval of a stream is therefore a rate that is never exceeded, not a poll
 * that always runs.
 *
 * <p>The other half of the same idea is that the workload grows with the number of browsers and
 * not with the throughput of the pipeline: whatever the sources report, one tick carries at
 * most what {@link #publish()} decides to publish, and a connection that cannot keep up loses
 * its oldest frames instead of the service its memory (see {@link SseStream}).
 *
 * <p>The endpoints are closed as the context closes rather than as the beans are destroyed:
 * {@link ContextClosedEvent} is published before the web server begins its graceful shutdown,
 * so a browser is let go of before the service starts waiting for the requests it holds.
 */
public abstract class SseEndpoint implements ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(SseEndpoint.class);

    private final String name;
    private final Duration interval;
    private final SseStream stream;
    private final TaskScheduler scheduler;
    private final Object monitor = new Object();

    /** The ticker of the endpoint, or {@code null} while no browser is connected. */
    private ScheduledFuture<?> ticker;

    protected SseEndpoint(String name, Duration interval, TaskScheduler scheduler, SseStream stream) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.interval = Objects.requireNonNull(interval, "interval must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.stream = Objects.requireNonNull(stream, "stream must not be null");
        this.stream.whenEmpty(this::connectionEnded);
    }

    /**
     * One tick of the endpoint: what it sends to every browser connected to it.
     *
     * <p>Runs on the ticker thread, never on a request thread.
     */
    protected abstract void publish();

    /**
     * Called when a browser connects to an endpoint that had no connection.
     *
     * <p>This is the moment a stream becomes live: an endpoint that reads a source which has
     * a position in it places that position at the live edge here.
     */
    protected void connectionOpened() {
    }

    /**
     * Opens one browser connection.
     *
     * @return the emitter the endpoint answers with
     * @throws StreamCapacityExceededException when the endpoint already serves its maximum
     */
    public final SseEmitter subscribe() {
        synchronized (monitor) {
            SseEmitter emitter = stream.subscribe();
            if (stream.subscriberCount() == 1) {
                connectionOpened();
                ticker = scheduler.scheduleWithFixedDelay(this::tick, interval);
                log.debug("stream {}: ticker started, interval={}", name, interval);
            }
            return emitter;
        }
    }

    /** One tick, from which no failure may escape: a thrown exception would end the ticker. */
    private void tick() {
        try {
            if (stream.subscriberCount() == 0) {
                return;
            }
            publish();
        } catch (RuntimeException failure) {
            log.warn("stream {}: a tick failed and the next one still runs", name, failure);
        }
    }

    /** The last connection of the endpoint ended: the ticker has nothing left to feed. */
    private void connectionEnded() {
        synchronized (monitor) {
            if (stream.subscriberCount() > 0 || ticker == null) {
                return;
            }
            ticker.cancel(false);
            ticker = null;
            log.debug("stream {}: ticker stopped, no browser is connected", name);
        }
    }

    /**
     * Lets go of every browser and stops the ticker, before the web server starts waiting for
     * the requests of a service that is going away.
     */
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        close();
    }

    /** Ends every connection of the endpoint. */
    public final void close() {
        synchronized (monitor) {
            if (ticker != null) {
                ticker.cancel(false);
                ticker = null;
            }
        }
        stream.close();
    }

    /**
     * @return the connections of this endpoint, for the endpoint that publishes into it
     */
    protected final SseStream stream() {
        return stream;
    }
}

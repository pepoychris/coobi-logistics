package com.coobi.logistics.logisticsapi.stream;

import com.coobi.logistics.logisticsapi.config.StreamProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The browsers connected to one SSE endpoint (MVP-6.1).
 *
 * <p>What a connection costs is bounded here, in three places, because a browser is a client
 * this service does not control:
 *
 * <ul>
 *   <li>at most {@code maxSubscribers} connections are accepted; the next one is refused
 *       instead of being queued, so the service holds a bounded number of sockets;
 *   <li>each connection has a buffer of a fixed size, and a browser that does not read it
 *       loses its oldest frame rather than the service its memory;
 *   <li>each connection is drained by a virtual thread of its own, so a browser that reads
 *       slowly blocks nothing but itself: the ticker that publishes the frames never waits on
 *       a socket, and no connection can slow down another.
 * </ul>
 *
 * <p>A frame is serialized once per publish, not once per connection, so the cost of a tick
 * grows with the size of the event and not with the number of browsers.
 */
public final class SseStream implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SseStream.class);

    /**
     * No container timeout: an SSE connection ends when the browser closes it, when a write
     * fails on it, or when the service shuts down - never because a clock said so. An idle
     * connection is kept alive by its heartbeat instead.
     */
    private static final long NO_TIMEOUT = 0L;

    private final String name;
    private final int maxSubscribers;
    private final int bufferSize;
    private final Duration heartbeatInterval;
    private final ObjectMapper json;
    private final Supplier<SseEmitter> emitters;
    private final Map<Long, Subscriber> subscribers = new ConcurrentHashMap<>();
    private final AtomicLong connections = new AtomicLong();
    private final Counter dropped;

    /** What to do when the last connection of the stream ends; set by the endpoint. */
    private volatile Runnable onEmpty = () -> {
    };

    public SseStream(String name, StreamProperties.Client client, ObjectMapper json, MeterRegistry meters) {
        this(name, client, json, meters, () -> new SseEmitter(NO_TIMEOUT));
    }

    /**
     * The same stream, with the emitters it creates supplied by the caller.
     *
     * <p>The seam exists for the tests of this class, which need a connection they can read
     * and fail on their own terms; a running service always creates plain emitters.
     */
    SseStream(
            String name,
            StreamProperties.Client client,
            ObjectMapper json,
            MeterRegistry meters,
            Supplier<SseEmitter> emitters) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        StreamProperties.Client settings = Objects.requireNonNull(client, "client must not be null");
        this.maxSubscribers = settings.maxSubscribers();
        this.bufferSize = settings.bufferSize();
        this.heartbeatInterval = settings.heartbeatInterval();
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.emitters = Objects.requireNonNull(emitters, "emitters must not be null");
        MeterRegistry registry = Objects.requireNonNull(meters, "meters must not be null");
        Gauge.builder("coobi.stream.subscribers", subscribers, open -> open.size())
                .description("Browser connections currently open on this SSE stream")
                .tag("stream", name)
                .register(registry);
        this.dropped = Counter.builder("coobi.stream.dropped")
                .description("Frames dropped because a browser connection did not read them")
                .tag("stream", name)
                .register(registry);
    }

    /**
     * Accepts one browser connection.
     *
     * @return the emitter the endpoint answers with
     * @throws StreamCapacityExceededException when the stream already serves its maximum
     */
    public SseEmitter subscribe() {
        long id = connections.incrementAndGet();
        SseEmitter emitter = emitters.get();
        Subscriber subscriber = new Subscriber(id, emitter);
        subscribers.put(id, subscriber);
        if (subscribers.size() > maxSubscribers) {
            // Two browsers arrived together and one of them is above the limit: the newest is
            // refused, so the connections that are already open keep the slots they hold.
            subscribers.remove(id);
            throw new StreamCapacityExceededException(name, maxSubscribers);
        }
        emitter.onCompletion(subscriber::close);
        emitter.onError(lost -> subscriber.close());
        emitter.onTimeout(subscriber::close);
        subscriber.start();
        log.debug("stream {}: connection {} accepted, {} open", name, id, subscribers.size());
        return emitter;
    }

    /**
     * Hands one event to every connection.
     *
     * <p>Does nothing when no browser is connected, which is what keeps an idle API idle: a
     * stream with no reader produces no frame, not a frame that is thrown away.
     *
     * @param eventName name of the SSE event
     * @param payload payload, serialized as JSON
     */
    public void publish(String eventName, Object payload) {
        if (subscribers.isEmpty()) {
            return;
        }
        String json;
        try {
            json = this.json.writeValueAsString(payload);
        } catch (JsonProcessingException notSerializable) {
            // One event that cannot be written must not cost a stream its ticker, and it must
            // not be half-sent either: it is named in the log and the stream continues.
            log.warn("stream {}: an event of type {} could not be serialized and was not sent", name, eventName,
                    notSerializable);
            return;
        }
        StreamFrame frame = StreamFrame.event(eventName, json);
        subscribers.values().forEach(subscriber -> subscriber.offer(frame));
    }

    /**
     * Registers what to do when the last connection ends.
     *
     * @param callback callback to run once the stream has no connection left
     */
    void whenEmpty(Runnable callback) {
        this.onEmpty = Objects.requireNonNull(callback, "callback must not be null");
    }

    /**
     * @return browsers connected right now
     */
    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * @return frames dropped so far because a connection did not read them
     */
    public double droppedFrames() {
        return dropped.count();
    }

    /**
     * Ends every connection, as the service shuts down.
     *
     * <p>A browser reconnects on its own, so closing is the polite answer: a connection that
     * was left open would hold a request of a server that is stopping.
     */
    @Override
    public void close() {
        new ArrayList<>(subscribers.values()).forEach(Subscriber::close);
    }

    /** One browser connection: its buffer, its pump thread and its lifecycle. */
    private final class Subscriber implements Runnable {

        private final long id;
        private final SseEmitter emitter;
        private final FrameBuffer frames;
        private final AtomicBoolean open = new AtomicBoolean(true);

        /** The thread that writes to this connection, once it has been started. */
        private volatile Thread pump;

        Subscriber(long id, SseEmitter emitter) {
            this.id = id;
            this.emitter = emitter;
            this.frames = new FrameBuffer(bufferSize);
        }

        void start() {
            pump = Thread.ofVirtual().name("sse-" + name + "-" + id).start(this);
        }

        /**
         * Sends to the connection, and only to it.
         *
         * <p>This runs on the virtual thread of the connection, so the write that blocks on a
         * slow socket blocks this pump and nothing else.
         */
        @Override
        public void run() {
            try {
                send(StreamFrame.opened());
                while (open.get()) {
                    StreamFrame frame = frames.poll(heartbeatInterval);
                    send(frame == null ? StreamFrame.keepAlive() : frame);
                }
            } catch (InterruptedException closing) {
                Thread.currentThread().interrupt();
            } catch (IOException lost) {
                log.debug("stream {}: connection {} was lost reason={}", name, id, lost.getMessage());
            } catch (RuntimeException failed) {
                log.debug("stream {}: connection {} failed reason={}", name, id, failed.toString());
            } finally {
                close();
            }
        }

        private void send(StreamFrame frame) throws IOException {
            emitter.send(frame.toSse());
        }

        void offer(StreamFrame frame) {
            if (!open.get()) {
                return;
            }
            if (frames.offer(frame)) {
                dropped.increment();
                log.debug("stream {}: connection {} fell behind and lost its oldest frame", name, id);
            }
        }

        /**
         * Ends this connection once, whoever ends it first: the pump, a lost socket, the end
         * of the request, or the shutdown of the service.
         */
        void close() {
            if (!open.compareAndSet(true, false)) {
                return;
            }
            subscribers.remove(id);
            Thread thread = pump;
            if (thread != null && thread != Thread.currentThread()) {
                // The pump is waiting for a frame: it is woken up so the connection is let go
                // of now instead of at its next heartbeat.
                thread.interrupt();
            }
            try {
                // A connection that is already completed - the usual case when this is the
                // completion callback of the framework - must not raise here.
                emitter.complete();
            } catch (RuntimeException alreadyEnded) {
                log.debug("stream {}: connection {} was already completed", name, id);
            }
            log.debug("stream {}: connection {} closed, {} open", name, id, subscribers.size());
            onEmpty.run();
        }
    }
}

package com.coobi.logistics.logisticsapi.stream;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One frame of a browser stream, as a client reads it.
 *
 * <p>A frame is either an event - a name and the JSON of the object the REST endpoint of the
 * same family reports - or a comment, which a browser ignores as an event and which is how a
 * quiet connection is kept alive. The frame is written by the Server-Sent Events support of the
 * framework rather than by a hand-built string, and {@link #wireText()} renders the very same
 * thing the builder writes: one place decides the format, and the documentation, the tests and
 * the client all see it.
 */
record StreamFrame(String event, String payload, Duration reconnect) {

    /**
     * How long a browser waits before reconnecting, sent once per connection.
     *
     * <p>A reconnect is a normal event here: a connection that ends - because a proxy closed it,
     * or because the service restarted - is reopened by the browser on its own, and the stream
     * then continues at the live edge while the history is a page fetched over REST.
     */
    private static final Duration RECONNECT_AFTER = Duration.ofSeconds(3);

    /**
     * How the payload of an event is written.
     *
     * <p>It picks the converter and, what matters here, the encoding: the JSON of an event is
     * sent as UTF-8 instead of as the default charset of the machine that happens to run the
     * service.
     */
    private static final MediaType PAYLOAD = new MediaType("text", "plain", StandardCharsets.UTF_8);

    StreamFrame {
        Objects.requireNonNull(payload, "payload must not be null");
        if (event != null && reconnect != null) {
            throw new IllegalArgumentException("an event does not carry a reconnect hint");
        }
    }

    /**
     * @param name name of the event, as a browser listens for it
     * @param json payload of the event, already serialized
     * @return the frame
     */
    static StreamFrame event(String name, String json) {
        return new StreamFrame(Objects.requireNonNull(name, "name must not be null"), json, null);
    }

    /**
     * @param text text after the colon of a comment
     * @return a frame a browser ignores as an event
     */
    static StreamFrame comment(String text) {
        return new StreamFrame(null, text, null);
    }

    /**
     * The first frame of a connection: a reconnect hint and a comment.
     *
     * <p>It carries no event, and it exists to commit the response: a browser reports a
     * connection as open when the response headers arrive, so a stream that stayed silent until
     * its first event would look closed for as long as nothing happened.
     *
     * @return the frame
     */
    static StreamFrame opened() {
        return new StreamFrame(null, "connected", RECONNECT_AFTER);
    }

    /**
     * @return a frame that keeps a connection alive while it has nothing else to say
     */
    static StreamFrame keepAlive() {
        return new StreamFrame(null, "keep-alive", null);
    }

    /**
     * @return the frame, as the Server-Sent Events support of the framework writes it
     */
    SseEmitter.SseEventBuilder toSse() {
        SseEmitter.SseEventBuilder frame = SseEmitter.event();
        if (reconnect != null) {
            frame = frame.reconnectTime(reconnect.toMillis());
        }
        return event == null ? frame.comment(payload) : frame.name(event).data(payload, PAYLOAD);
    }

    /**
     * The frame as the bytes of a response: what the documentation shows and what the tests of
     * the endpoints assert, built by the same builder that sends it.
     *
     * @return the text of the frame, as a client reads it
     */
    String wireText() {
        StringBuilder text = new StringBuilder();
        toSse().build().forEach(part -> text.append(part.getData()));
        return text.toString();
    }
}

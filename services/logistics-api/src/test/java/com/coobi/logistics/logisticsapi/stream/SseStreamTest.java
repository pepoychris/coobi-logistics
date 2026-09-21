package com.coobi.logistics.logisticsapi.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coobi.logistics.logisticsapi.config.StreamProperties;
import com.coobi.logistics.logisticsapi.support.Await;
import com.coobi.logistics.logisticsapi.support.RecordingEmitter;
import com.coobi.logistics.logisticsapi.support.StallingEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * MVP-6.1: what a connected browser is allowed to cost and what it is allowed to receive.
 */
class SseStreamTest {

    private static final StreamProperties.Client CLIENT =
            new StreamProperties.Client(2, 8, Duration.ofMillis(50));

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsUpToTheConfiguredNumberOfConnections() {
        SseStream stream = stream(() -> new RecordingEmitter());

        stream.subscribe();
        stream.subscribe();

        assertThat(stream.subscriberCount()).isEqualTo(2);
        assertThatThrownBy(stream::subscribe)
                .isInstanceOf(StreamCapacityExceededException.class)
                .hasMessageContaining("events")
                .hasMessageContaining("2");
        assertThat(stream.subscriberCount()).as("the refused connection is not held").isEqualTo(2);

        stream.close();
    }

    @Test
    void publishesNothingWhenNoBrowserIsConnected() {
        SseStream stream = stream(() -> new RecordingEmitter());

        stream.publish("alert", java.util.Map.of("vehicleId", "TRUCK-00001"));

        assertThat(stream.droppedFrames())
                .as("a frame nobody can receive is not produced, let alone dropped")
                .isZero();
        assertThat(subscribers()).isZero();
    }

    @Test
    void sendsOneEventToEveryConnection() throws Exception {
        List<RecordingEmitter> browsers = new ArrayList<>();
        SseStream stream = stream(() -> {
            RecordingEmitter browser = new RecordingEmitter();
            browsers.add(browser);
            return browser;
        });

        stream.subscribe();
        stream.subscribe();
        stream.publish("alert", java.util.Map.of("vehicleId", "TRUCK-00001"));

        for (RecordingEmitter browser : browsers) {
            Await.until("the browser to read the event", () -> browser.frames().size() >= 2);
            assertThat(browser.frames().get(1)).isEqualTo("event:alert\ndata:{\"vehicleId\":\"TRUCK-00001\"}\n\n");
        }

        stream.close();
    }

    @Test
    void givesUpTheOldestFrameOfAConnectionThatFellBehind() throws Exception {
        // A browser that never reads what it is sent is what a suspended laptop looks like
        // from here: the pump blocks on the socket and the buffer is the only thing that grows.
        StallingEmitter stalled = new StallingEmitter();
        SseStream stream = new SseStream(
                "events", new StreamProperties.Client(2, 1, Duration.ofMillis(50)), json, meters, () -> stalled);

        stream.subscribe();
        Await.until("the connection to be open", () -> subscribers() == 1);
        // The connection is quietly keeping itself alive, and that write is the one that never
        // finishes: from here on nothing it is sent can be read.
        assertThat(stalled.awaitStalled()).as("the connection stopped reading").isTrue();
        stream.publish("alert", java.util.Map.of("sequence", 1));
        stream.publish("alert", java.util.Map.of("sequence", 2));
        stream.publish("alert", java.util.Map.of("sequence", 3));

        assertThat(stream.droppedFrames())
                .as("a connection that does not read loses frames instead of holding memory")
                .isEqualTo(2.0);
        assertThat(dropped()).isEqualTo(stream.droppedFrames());

        stalled.release();
        stream.close();
    }

    @Test
    void endsEveryConnectionWhenTheServiceCloses() {
        SseStream stream = stream(() -> new RecordingEmitter());

        SseEmitter first = stream.subscribe();
        SseEmitter second = stream.subscribe();

        stream.close();

        assertThat(stream.subscriberCount()).isZero();
        assertThat(subscribers()).isZero();
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
    }

    private SseStream stream(java.util.function.Supplier<SseEmitter> emitters) {
        return new SseStream("events", CLIENT, json, meters, emitters);
    }

    private double subscribers() {
        Gauge gauge = meters.get("coobi.stream.subscribers").tag("stream", "events").gauge();
        return gauge == null ? 0 : gauge.value();
    }

    private double dropped() {
        return meters.get("coobi.stream.dropped").tag("stream", "events").counter().count();
    }
}

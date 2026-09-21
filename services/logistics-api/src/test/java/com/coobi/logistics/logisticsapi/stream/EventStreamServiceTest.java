package com.coobi.logistics.logisticsapi.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.logisticsapi.alert.AlertResponse;
import com.coobi.logistics.logisticsapi.alert.AlertSeverity;
import com.coobi.logistics.logisticsapi.alert.AlertType;
import com.coobi.logistics.logisticsapi.config.StreamProperties;
import com.coobi.logistics.logisticsapi.support.Await;
import com.coobi.logistics.logisticsapi.support.ManualTaskScheduler;
import com.coobi.logistics.logisticsapi.support.RecordingEmitter;
import com.coobi.logistics.logisticsapi.vehicle.VehicleResponse;
import com.coobi.logistics.logisticsapi.vehicle.VehicleStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * MVP-6.1 without a browser and without a clock: what the endpoint publishes, when it stops,
 * and the fact that it does nothing at all while nobody is connected.
 */
class EventStreamServiceTest {

    private static final Duration TICK = Duration.ofMillis(100);
    private static final Duration HEARTBEAT = Duration.ofMillis(50);
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-21T09:15:00Z");

    private final StreamProperties properties = new StreamProperties(
            new StreamProperties.Events(TICK, 3, 2),
            new StreamProperties.Statistics(Duration.ofSeconds(1)),
            new StreamProperties.Client(4, 8, HEARTBEAT));

    /**
     * The JSON of the service, without a context around it: the events carry an
     * {@link Instant}, which the mapper the service injects writes as the ISO-8601 text the
     * REST endpoints write, and which a mapper built by hand cannot write at all.
     */
    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    private final ManualTaskScheduler scheduler = new ManualTaskScheduler();
    private final RecordingEmitter browser = new RecordingEmitter();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ScriptedFeed feed = new ScriptedFeed();
    private final EventStreamService service = new EventStreamService(
            properties,
            scheduler,
            new SseStream(EventStreamService.STREAM, properties.client(), json, meters, () -> browser),
            feed);

    @Test
    void opensAConnectionAndSendsTheEventsOfEveryTick() throws Exception {
        service.subscribe();

        assertThat(browser.nextFrame(Duration.ofSeconds(5)))
                .as("a browser reports a connection as open when the headers arrive, so the "
                        + "connection is opened with a frame a client ignores as an event")
                .isEqualTo("retry:3000\n:connected\n\n");

        feed.report(StreamEvent.alert(alert(7L, "TRUCK-00001")), StreamEvent.vehicle(vehicle("TRUCK-00002")));
        scheduler.runTicks();

        assertThat(awaitEvent("alert"))
                .startsWith("event:alert\ndata:{")
                .contains("\"vehicleId\":\"TRUCK-00001\"");
        assertThat(awaitEvent("vehicle"))
                .startsWith("event:vehicle\ndata:{")
                .contains("\"vehicleId\":\"TRUCK-00002\"");
    }

    @Test
    void ticksAtTheConfiguredInterval() {
        service.subscribe();

        assertThat(scheduler.scheduled()).as("one ticker for the endpoint").isEqualTo(1);
        assertThat(scheduler.intervalOf(0)).isEqualTo(TICK);
    }

    @Test
    void doesNothingAtAllWhileNoBrowserIsConnected() {
        scheduler.runTicks();
        scheduler.runTicks();

        assertThat(feed.polls())
                .as("a stream nobody watches must not read the read model")
                .isZero();
        assertThat(feed.starts()).isZero();
        assertThat(subscribers()).isZero();
        assertThat(scheduler.scheduled()).as("no ticker exists before the first connection").isZero();
    }

    @Test
    void readsTheFeedFromTheLiveEdgeWhenABrowserConnects() {
        service.subscribe();
        scheduler.runTicks();

        assertThat(feed.starts()).as("a connection makes the stream live").isEqualTo(1);
        assertThat(feed.polls()).isEqualTo(1);
    }

    @Test
    void stopsTickingWhenTheLastBrowserGoesAway() {
        service.subscribe();
        scheduler.runTicks();
        int pollsWhileConnected = feed.polls();

        // The browser closed the page: the next write to it fails, and that is how a gone
        // connection is noticed.
        browser.fail();
        Await.until("the connection to be given up", () -> subscribers() == 0);

        scheduler.runTicks();
        assertThat(scheduler.cancelled()).as("the ticker of an endpoint nobody watches is cancelled").isEqualTo(1);
        assertThat(feed.polls()).as("and it stops reading the read model").isEqualTo(pollsWhileConnected);
    }

    @Test
    void endsItsConnectionsAndItsTickerWhenTheServiceShutsDown() {
        service.subscribe();

        service.close();

        assertThat(subscribers()).isZero();
        assertThat(scheduler.cancelled()).isEqualTo(1);
        scheduler.runTicks();
        assertThat(feed.polls()).isZero();
    }

    private double subscribers() {
        Gauge gauge = meters.get("coobi.stream.subscribers")
                .tag("stream", EventStreamService.STREAM)
                .gauge();
        return gauge == null ? 0 : gauge.value();
    }

    /**
     * Reads until an event arrives, skipping the frames that carry no event: a connection
     * keeps itself alive while it has nothing else to say.
     */
    private String awaitEvent(String name) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new AssertionError("the " + name + " event did not arrive");
            }
            String frame = browser.nextFrame(Duration.ofNanos(left));
            if (frame == null) {
                throw new AssertionError("the " + name + " event did not arrive, only heartbeats did");
            }
            if (frame.startsWith("event:" + name + "\n")) {
                return frame;
            }
        }
    }

    private AlertResponse alert(long id, String vehicleId) {
        return new AlertResponse(
                id,
                UUID.randomUUID(),
                vehicleId,
                AlertType.SPEEDING_DETECTED,
                AlertSeverity.WARNING,
                OCCURRED_AT,
                OCCURRED_AT.plusSeconds(1),
                json.createObjectNode().put("speed", 131.5).put("threshold", 120.0));
    }

    private VehicleResponse vehicle(String vehicleId) {
        return new VehicleResponse(
                vehicleId, 39.4699, -0.3763, 80.0, 214.5, VehicleStatus.MOVING, OCCURRED_AT, OCCURRED_AT);
    }

    /** A feed whose events the test writes. */
    private static final class ScriptedFeed implements StreamEventFeed {

        private final List<StreamEvent> pending = new ArrayList<>();
        private int starts;
        private int polls;

        void report(StreamEvent... events) {
            pending.addAll(Arrays.asList(events));
        }

        int starts() {
            return starts;
        }

        int polls() {
            return polls;
        }

        @Override
        public void start() {
            starts++;
        }

        @Override
        public List<StreamEvent> poll(int maxAlerts, int maxVehicles) {
            polls++;
            List<StreamEvent> batch = List.copyOf(pending);
            pending.clear();
            return batch;
        }
    }
}

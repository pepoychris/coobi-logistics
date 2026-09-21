package com.coobi.logistics.logisticsapi.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.coobi.logistics.logisticsapi.config.StreamProperties;
import com.coobi.logistics.logisticsapi.statistics.StatisticsResponse;
import com.coobi.logistics.logisticsapi.statistics.StatisticsService;
import com.coobi.logistics.logisticsapi.support.ManualTaskScheduler;
import com.coobi.logistics.logisticsapi.support.RecordingEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * MVP-6.2 without a browser and without a clock: the statistics the stream sends are the ones
 * the statistics service of MVP-5.4 reports, at the interval the stream is configured with.
 */
class StatisticsStreamServiceTest {

    private static final Duration INTERVAL = Duration.ofMillis(250);

    private final StreamProperties properties = new StreamProperties(
            new StreamProperties.Events(Duration.ofSeconds(1), 20, 30),
            new StreamProperties.Statistics(INTERVAL),
            new StreamProperties.Client(4, 8, Duration.ofSeconds(1)));
    private final ManualTaskScheduler scheduler = new ManualTaskScheduler();
    private final RecordingEmitter browser = new RecordingEmitter();
    private final StatisticsService statistics = Mockito.mock(StatisticsService.class);
    private final StatisticsStreamService service = new StatisticsStreamService(
            properties,
            scheduler,
            new SseStream(
                    StatisticsStreamService.STREAM,
                    properties.client(),
                    new ObjectMapper(),
                    new SimpleMeterRegistry(),
                    () -> browser),
            statistics);

    @Test
    void sendsTheStatisticsOfEveryTick() throws Exception {
        given(statistics.current()).willReturn(new StatisticsResponse(12_938_281L, 12_492L, 5_000L, 281L, 8_271L));

        service.subscribe();
        scheduler.runTicks();

        assertThat(browser.nextFrame(Duration.ofSeconds(5))).startsWith("retry:3000");
        assertThat(browser.nextFrame(Duration.ofSeconds(5)))
                .startsWith("event:statistics\ndata:{")
                .contains("\"processedEvents\":12938281")
                .contains("\"eventsPerSecond\":12492")
                .contains("\"activeVehicles\":5000")
                .contains("\"alertsGenerated\":281")
                .contains("\"uptimeSeconds\":8271");
    }

    @Test
    void ticksAtTheConfiguredInterval() {
        service.subscribe();

        assertThat(scheduler.scheduled()).isEqualTo(1);
        assertThat(scheduler.intervalOf(0)).isEqualTo(INTERVAL);
    }

    @Test
    void reportsASourceItCannotReadTheWayTheRestEndpointDoes() throws Exception {
        given(statistics.current()).willReturn(new StatisticsResponse(null, null, 0L, 0L, 12L));

        service.subscribe();
        scheduler.runTicks();
        browser.nextFrame(Duration.ofSeconds(5));

        assertThat(browser.nextFrame(Duration.ofSeconds(5)))
                .as("an unavailable source is reported as absent in the stream too")
                .contains("\"processedEvents\":null")
                .contains("\"eventsPerSecond\":null");
    }

    @Test
    void stopsTickingWhenTheLastBrowserGoesAway() {
        service.subscribe();

        service.close();

        assertThat(scheduler.cancelled()).isEqualTo(1);
        scheduler.runTicks();
        Mockito.verify(statistics, Mockito.never()).current();
    }
}

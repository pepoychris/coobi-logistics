package com.coobi.logistics.logisticsapi.stream;

import com.coobi.logistics.logisticsapi.config.StreamProperties;
import com.coobi.logistics.logisticsapi.statistics.StatisticsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * {@code GET /api/v1/stream/statistics} (MVP-6.2).
 *
 * <p>Every tick asks the statistics service of MVP-5.4 for the live values and sends them, so
 * a browser draws a live dashboard without polling and without a second implementation of what
 * a statistic is: this endpoint adds a rate to an answer that already exists, and reports every
 * value - including the ones whose source cannot be read - exactly as {@code /statistics} does.
 *
 * <p>The interval is the only cost a browser adds: one read of the database and one read of the
 * stream processor per interval, however many browsers are connected, because every connection
 * receives the same frame.
 */
@Service
public class StatisticsStreamService extends SseEndpoint {

    /** Name of the stream, which is also the name of its SSE event. */
    public static final String STREAM = "statistics";

    private final StatisticsService statistics;

    @Autowired
    public StatisticsStreamService(
            StreamProperties properties,
            @Qualifier("streamScheduler") TaskScheduler scheduler,
            ObjectMapper json,
            MeterRegistry meters,
            StatisticsService statistics) {
        this(properties, scheduler, new SseStream(STREAM, properties.client(), json, meters), statistics);
    }

    /**
     * The same endpoint over the connections supplied by the caller, which is how a test
     * drives it without a browser and without a clock.
     */
    StatisticsStreamService(
            StreamProperties properties,
            TaskScheduler scheduler,
            SseStream stream,
            StatisticsService statistics) {
        super(STREAM, properties.statistics().interval(), scheduler, stream);
        this.statistics = Objects.requireNonNull(statistics, "statistics must not be null");
    }

    @Override
    protected void publish() {
        stream().publish(STREAM, statistics.current());
    }
}

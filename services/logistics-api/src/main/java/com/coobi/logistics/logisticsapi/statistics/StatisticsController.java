package com.coobi.logistics.logisticsapi.statistics;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/statistics} (MVP-5.4).
 *
 * <p>The endpoint has no parameter: it reports the live state of the stack as it is, and a
 * browser polls it at its own pace. The fields and their sources are documented in
 * docs/logistics-api.md.
 */
@RestController
@RequestMapping(path = "/api/v1/statistics", produces = MediaType.APPLICATION_JSON_VALUE)
public class StatisticsController {

    private final StatisticsService statistics;

    public StatisticsController(StatisticsService statistics) {
        this.statistics = statistics;
    }

    /**
     * @return the live statistics of the stack
     */
    @GetMapping
    public StatisticsResponse current() {
        return statistics.current();
    }
}

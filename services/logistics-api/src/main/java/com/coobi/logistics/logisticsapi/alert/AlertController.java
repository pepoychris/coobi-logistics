package com.coobi.logistics.logisticsapi.alert;

import com.coobi.logistics.logisticsapi.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/alerts} and {@code GET /api/v1/alerts/{id}} (MVP-5.3).
 *
 * <p>The list accepts the three filters of the milestone - {@code vehicleId}, {@code type}
 * and {@code severity} - independently and in any combination, plus the paging parameters.
 * A filter value outside an enumeration is rejected with {@code 400}, and an unknown alert
 * with {@code 404}.
 */
@RestController
@RequestMapping(path = "/api/v1/alerts", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class AlertController {

    private final AlertQueryService alerts;

    public AlertController(AlertQueryService alerts) {
        this.alerts = alerts;
    }

    /**
     * One page of the stored alerts, newest first.
     *
     * @param vehicleId optional vehicle filter; omitted or blank means every vehicle
     * @param type optional alert type filter
     * @param severity optional severity filter
     * @param page zero-based page index, {@code 0} by default
     * @param size page size, {@code 20} by default and at most the documented maximum
     * @return the page of alerts
     */
    @GetMapping
    public PageResponse<AlertResponse> list(
            @RequestParam(name = "vehicleId", required = false) String vehicleId,
            @RequestParam(name = "type", required = false) AlertType type,
            @RequestParam(name = "severity", required = false) AlertSeverity severity,
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = "must be greater than or equal to 0") int page,
            @RequestParam(name = "size", defaultValue = "20")
            @Min(value = 1, message = "must be greater than or equal to 1")
            @Max(
                    value = AlertQueryService.MAX_PAGE_SIZE,
                    message = "must be less than or equal to " + AlertQueryService.MAX_PAGE_SIZE) int size) {
        return alerts.page(vehicleId, type, severity, page, size);
    }

    /**
     * One stored alert.
     *
     * @param id surrogate key of the alert
     * @return the alert
     */
    @GetMapping("/{id}")
    public AlertResponse byId(@PathVariable(name = "id") long id) {
        return alerts.byId(id);
    }
}

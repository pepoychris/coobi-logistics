package com.coobi.logistics.logisticsapi.alert;

import com.coobi.logistics.logisticsapi.web.ApiNotFoundException;
import com.coobi.logistics.logisticsapi.web.PageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The alert views of MVP-5.3.
 *
 * <p>Alerts are read newest first, with the surrogate key as the tie breaker, so a page
 * boundary is stable even when two alerts share the instant of the telemetry event. The
 * three filters are independent and come together in any combination; a blank vehicle id is
 * treated as no filter, because an empty query parameter means "not asked for" in a browser
 * form, and filtering by it would answer an empty page that looks like missing data.
 */
@Service
@Transactional(readOnly = true)
public class AlertQueryService {

    /** Largest page a caller may ask for, shared with the controller constraint. */
    public static final int MAX_PAGE_SIZE = 100;

    private static final Sort ORDER = Sort.by(
            Sort.Order.desc("occurredAt"),
            Sort.Order.desc("id"));

    private final AlertRepository alerts;
    private final AlertMetadata metadata;

    public AlertQueryService(AlertRepository alerts, ObjectMapper objectMapper) {
        this.alerts = Objects.requireNonNull(alerts, "alerts must not be null");
        this.metadata = new AlertMetadata(Objects.requireNonNull(objectMapper, "objectMapper must not be null"));
    }

    /**
     * One page of the stored alerts, newest first.
     *
     * @param vehicleId vehicle to filter by; blank or {@code null} means every vehicle
     * @param type alert type to filter by, or {@code null} for every type
     * @param severity severity to filter by, or {@code null} for every severity
     * @param page zero-based page index
     * @param size page size, never above {@link #MAX_PAGE_SIZE}
     * @return the page of alerts as the API reports it
     */
    public PageResponse<AlertResponse> page(
            String vehicleId,
            AlertType type,
            AlertSeverity severity,
            int page,
            int size) {
        Page<AlertRecord> found = alerts.findAll(
                AlertRepository.filters(normalise(vehicleId), type, severity),
                PageRequest.of(page, size, ORDER));
        return PageResponse.of(found.map(this::toResponse));
    }

    /**
     * One stored alert.
     *
     * @param id surrogate key of the alert
     * @return the alert as the API reports it
     * @throws ApiNotFoundException when no alert has that key
     */
    public AlertResponse byId(long id) {
        return alerts.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ApiNotFoundException("no alert with id " + id));
    }

    /**
     * The key of the newest stored alert.
     *
     * <p>It is where the live event stream (MVP-6.1) starts, so a stream carries the alerts that
     * arrive while it is open instead of replaying the history this paged endpoint serves.
     *
     * @return the highest stored alert key, or {@code 0} when no alert is stored
     */
    public long liveEdge() {
        return alerts.findFirstByOrderByIdDesc().map(AlertRecord::getId).orElse(0L);
    }

    /**
     * The newest alerts stored after one key, newest first.
     *
     * @param id key to start after
     * @param limit maximum number of alerts to answer with; when more were stored, the newest
     *        ones are the ones returned, so a reader that streams them stays at the live edge
     *        instead of falling behind by the size of a burst
     * @return the alerts, newest first, at most {@code limit} of them
     */
    public List<AlertResponse> newerThan(long id, int limit) {
        return alerts.findByIdGreaterThanOrderByIdDesc(id, PageRequest.of(0, limit)).stream()
                .map(this::toResponse)
                .toList();
    }

    private static String normalise(String vehicleId) {
        return StringUtils.hasText(vehicleId) ? vehicleId : null;
    }

    private AlertResponse toResponse(AlertRecord alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getEventId(),
                alert.getVehicleId(),
                alert.getType(),
                alert.getSeverity(),
                alert.getOccurredAt(),
                alert.getCreatedAt(),
                metadata.read(alert.getId(), alert.getMetadata()));
    }
}

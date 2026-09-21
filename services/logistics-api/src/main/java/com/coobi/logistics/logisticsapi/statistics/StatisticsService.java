package com.coobi.logistics.logisticsapi.statistics;

import com.coobi.logistics.logisticsapi.alert.AlertRepository;
import com.coobi.logistics.logisticsapi.vehicle.VehicleLatestStateRepository;
import com.coobi.logistics.logisticsapi.vehicle.VehicleStatus;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The live statistics of MVP-5.4.
 *
 * <p>The database answers what it knows - which vehicles are moving and how many alerts are
 * stored - and the stream processor answers what it knows: how many records it has processed.
 * Nothing is estimated: a value whose source cannot be read is reported as absent, which is
 * the only honest answer, and the response documents the source of every field.
 */
@Service
@Transactional(readOnly = true)
public class StatisticsService {

    private final VehicleLatestStateRepository vehicles;
    private final AlertRepository alerts;
    private final StreamProcessorMetrics metrics;
    private final UptimeClock uptime;

    public StatisticsService(
            VehicleLatestStateRepository vehicles,
            AlertRepository alerts,
            StreamProcessorMetrics metrics,
            UptimeClock uptime) {
        this.vehicles = Objects.requireNonNull(vehicles, "vehicles must not be null");
        this.alerts = Objects.requireNonNull(alerts, "alerts must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.uptime = Objects.requireNonNull(uptime, "uptime must not be null");
    }

    /**
     * Reads every source once and reports what they answered.
     *
     * @return the current statistics
     */
    public StatisticsResponse current() {
        Optional<StreamProcessorMetrics.ProcessedEvents> processed = metrics.read();
        return new StatisticsResponse(
                processed.map(StreamProcessorMetrics.ProcessedEvents::total).orElse(null),
                processed.map(StreamProcessorMetrics.ProcessedEvents::perSecond).orElse(null),
                vehicles.countByStatus(VehicleStatus.MOVING),
                alerts.count(),
                uptime.uptimeSeconds());
    }
}

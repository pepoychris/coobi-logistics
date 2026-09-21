package com.coobi.logistics.logisticsapi.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.coobi.logistics.logisticsapi.alert.AlertRepository;
import com.coobi.logistics.logisticsapi.vehicle.VehicleLatestStateRepository;
import com.coobi.logistics.logisticsapi.vehicle.VehicleStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * MVP-5.4: every field of the response comes from a named source, and a source that cannot
 * be read is reported as absent.
 */
class StatisticsServiceTest {

    private final VehicleLatestStateRepository vehicles = Mockito.mock(VehicleLatestStateRepository.class);
    private final AlertRepository alerts = Mockito.mock(AlertRepository.class);
    private final StreamProcessorMetrics metrics = Mockito.mock(StreamProcessorMetrics.class);
    private final UptimeClock uptime = Mockito.mock(UptimeClock.class);

    private final StatisticsService statistics = new StatisticsService(vehicles, alerts, metrics, uptime);

    @Test
    void reportsWhatEverySourceAnswered() {
        given(metrics.read()).willReturn(Optional.of(new StreamProcessorMetrics.ProcessedEvents(12_938_281L, 12_492L)));
        given(vehicles.countByStatus(VehicleStatus.MOVING)).willReturn(5_000L);
        given(alerts.count()).willReturn(281L);
        given(uptime.uptimeSeconds()).willReturn(8_271L);

        StatisticsResponse current = statistics.current();

        assertThat(current.processedEvents()).isEqualTo(12_938_281L);
        assertThat(current.eventsPerSecond()).isEqualTo(12_492L);
        assertThat(current.activeVehicles()).isEqualTo(5_000L);
        assertThat(current.alertsGenerated()).isEqualTo(281L);
        assertThat(current.uptimeSeconds()).isEqualTo(8_271L);
    }

    @Test
    void countsTheVehiclesThatAreMoving() {
        given(metrics.read()).willReturn(Optional.empty());
        given(vehicles.countByStatus(VehicleStatus.MOVING)).willReturn(3L);
        given(alerts.count()).willReturn(1L);
        given(uptime.uptimeSeconds()).willReturn(5L);

        assertThat(statistics.current().activeVehicles()).isEqualTo(3L);

        verify(vehicles).countByStatus(VehicleStatus.MOVING);
    }

    @Test
    void reportsAProcessorThatCannotBeReadAsAbsent() {
        given(metrics.read()).willReturn(Optional.empty());
        given(vehicles.countByStatus(VehicleStatus.MOVING)).willReturn(0L);
        given(alerts.count()).willReturn(0L);
        given(uptime.uptimeSeconds()).willReturn(11L);

        StatisticsResponse current = statistics.current();

        assertThat(current.processedEvents()).isNull();
        assertThat(current.eventsPerSecond()).isNull();
        assertThat(current.activeVehicles()).isZero();
        assertThat(current.alertsGenerated()).isZero();
        assertThat(current.uptimeSeconds()).isEqualTo(11L);
    }

    @Test
    void reportsNoRateBeforeASecondReadingOfTheCounter() {
        given(metrics.read()).willReturn(Optional.of(new StreamProcessorMetrics.ProcessedEvents(42L, null)));
        given(vehicles.countByStatus(VehicleStatus.MOVING)).willReturn(0L);
        given(alerts.count()).willReturn(0L);
        given(uptime.uptimeSeconds()).willReturn(1L);

        StatisticsResponse current = statistics.current();

        assertThat(current.processedEvents()).isEqualTo(42L);
        assertThat(current.eventsPerSecond()).isNull();
    }
}

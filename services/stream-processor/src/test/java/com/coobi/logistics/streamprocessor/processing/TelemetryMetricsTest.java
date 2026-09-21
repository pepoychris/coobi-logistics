package com.coobi.logistics.streamprocessor.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.streamprocessor.event.AlertType;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MVP-8.1: the meters the pipeline registers, and the contract of their names. */
class TelemetryMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TelemetryMetrics metrics = new TelemetryMetrics(meterRegistry);

    @Test
    void registersTheFiveDocumentedMetricsWithTheirTypes() {
        assertThat(meterRegistry.get(TelemetryMetrics.RECEIVED_METRIC).counter()).isNotNull();
        assertThat(meterRegistry.get(TelemetryMetrics.PROCESSED_METRIC).counter()).isNotNull();
        assertThat(meterRegistry.get(TelemetryMetrics.FAILED_METRIC).counter()).isNotNull();
        assertThat(meterRegistry.get(TelemetryMetrics.PROCESSING_DURATION_METRIC).timer()).isNotNull();
        assertThat(alertCounterNames())
                .containsExactlyInAnyOrderElementsOf(
                        List.of(AlertType.SPEEDING.name(), AlertType.VEHICLE_STOPPED.name()));
    }

    @Test
    void describesEveryMeterSoTheEndpointExplainsWhatItCounts() {
        assertThat(meterRegistry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getDescription())
                        .as("description of %s", meter.getId().getName())
                        .isNotBlank());
    }

    @Test
    void countsOneAlertPerTypeAndKeepsTheTypesApart() {
        for (AlertType type : AlertType.values()) {
            metrics.alertGenerated(type);
        }

        for (AlertType type : AlertType.values()) {
            assertThat(meterRegistry.get(TelemetryMetrics.ALERTS_METRIC)
                            .tag("type", type.name())
                            .counter()
                            .count())
                    .isEqualTo(1.0);
        }
    }

    @Test
    void sharesOneMeterPerNameWhenTheStagesRegisterOnTheSameRegistry() {
        TelemetryMetrics otherStage = new TelemetryMetrics(meterRegistry);

        metrics.received();
        otherStage.received();

        assertThat(meterRegistry.get(TelemetryMetrics.RECEIVED_METRIC).counter().count())
                .isEqualTo(2.0);
        assertThat(meterRegistry.getMeters().stream()
                        .map(meter -> meter.getId().getName())
                        .filter(TelemetryMetrics.RECEIVED_METRIC::equals))
                .hasSize(1);
    }

    /** The {@code type} tag values of the alert counter, in registration order. */
    private List<String> alertCounterNames() {
        return meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getType() == Meter.Type.COUNTER)
                .filter(meter -> TelemetryMetrics.ALERTS_METRIC.equals(meter.getId().getName()))
                .map(meter -> meter.getId().getTag("type"))
                .toList();
    }
}

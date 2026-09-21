package com.coobi.logistics.logisticsapi.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One alert stored by the processor - the {@code alerts} table of MVP-4.3.
 *
 * <p>Like the latest vehicle state, this is a read model of another service: the rows are
 * written by the stream processor, keyed by {@code eventId} for idempotency, and this
 * service only reads them.
 *
 * <p>{@code metadata} holds the {@code AlertData} of the alert contract verbatim. It is kept
 * as text here because the API hands it to the client as JSON (see {@link AlertQueryService});
 * the service never writes the column, so no object mapping stands between the stored
 * document and the response.
 */
@Entity
@Table(name = "alerts")
public class AlertRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** {@code eventId} of the alert contract: the idempotency key of the table. */
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "vehicle_id", nullable = false, length = 64)
    private String vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 64)
    private AlertType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 32)
    private AlertSeverity severity;

    /** Instant of the telemetry event that triggered the alert. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Instant the row was written. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** {@code AlertData} of the alert contract, stored verbatim as {@code jsonb}. */
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private String metadata;

    /** Required by Hibernate; the API never builds one of these itself. */
    protected AlertRecord() {
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public AlertType getType() {
        return type;
    }

    public AlertSeverity getSeverity() {
        return severity;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getMetadata() {
        return metadata;
    }
}

package com.coobi.logistics.streamprocessor.persistence;

import com.coobi.logistics.streamprocessor.event.AlertEvent;
import com.coobi.logistics.streamprocessor.processing.VehicleState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC implementation of {@link TelemetryPersistence} (MVP-4.2 and MVP-4.3).
 *
 * <p>The SQL is written in ISO form and relies on the unique constraints of the migrations
 * instead of PostgreSQL-only upsert syntax, so the same statements run on PostgreSQL - the
 * production database - and on the in-memory database in PostgreSQL mode the tests use. That
 * is what lets the idempotency of the production statements be verified without a server.
 *
 * <p>Upsert of the vehicle and its state:
 *
 * <ol>
 *   <li>insert the vehicle row when the vehicle is new;
 *   <li>update the single latest-state row;
 *   <li>insert it when the update matched nothing, which is the first telemetry of a
 *       vehicle.
 * </ol>
 *
 * <p>Two writers can race on the same vehicle (a rebalance overlap, or a retried record),
 * and the loser of that race hits a unique constraint. The losing statement is detected as
 * {@link DuplicateKeyException} and the whole upsert is retried once, which then takes the
 * update branch. Idempotency therefore does not depend on the racing writer being
 * serialized: the constraints are the backstop.
 *
 * <p>Both operations are executed on the calling thread, which for this service is a Kafka
 * Streams thread. A failing write is not swallowed: the exception propagates, Kafka Streams
 * retries the record against the same state, and the retry is harmless because every write
 * here is idempotent.
 */
public class JdbcTelemetryPersistence implements TelemetryPersistence {

    private static final Logger log = LoggerFactory.getLogger(JdbcTelemetryPersistence.class);

    private static final String SELECT_VEHICLE = "SELECT id FROM vehicles WHERE vehicle_id = ?";

    private static final String INSERT_VEHICLE =
            "INSERT INTO vehicles (vehicle_id, created_at) VALUES (?, ?)";

    private static final String UPDATE_LATEST_STATE = "UPDATE vehicle_latest_state SET"
            + " latitude = ?, longitude = ?, speed = ?, heading = ?, status = ?, last_update = ?, updated_at = ?"
            + " WHERE vehicle_id = ?";

    private static final String INSERT_LATEST_STATE = "INSERT INTO vehicle_latest_state"
            + " (vehicle_id, latitude, longitude, speed, heading, status, last_update, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String INSERT_ALERT = "INSERT INTO alerts"
            + " (event_id, vehicle_id, type, severity, occurred_at, created_at, metadata)"
            + " VALUES (CAST(? AS UUID), ?, ?, ?, ?, ?, CAST(? AS JSONB))";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdbcTelemetryPersistence(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactions =
                new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void recordVehicleState(String vehicleId, VehicleState state) {
        Objects.requireNonNull(vehicleId, "vehicleId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        try {
            // The vehicle row and its state row belong together, so they are one unit of work.
            transactions.executeWithoutResult(status -> upsertVehicleState(vehicleId, state));
        } catch (DuplicateKeyException concurrentWriter) {
            log.warn(
                    "retrying the vehicle state of a vehicle written concurrently vehicle-id={} reason={}",
                    vehicleId,
                    concurrentWriter.getMostSpecificCause().getMessage());
            transactions.executeWithoutResult(status -> upsertVehicleState(vehicleId, state));
        }
    }

    @Override
    public void recordAlert(AlertEvent alert) {
        Objects.requireNonNull(alert, "alert must not be null");
        try {
            jdbcTemplate.update(
                    INSERT_ALERT,
                    alert.eventId().toString(),
                    alert.vehicleId(),
                    alert.eventType(),
                    alert.severity().name(),
                    Timestamp.from(alert.timestamp()),
                    Timestamp.from(clock.instant()),
                    metadata(alert));
        } catch (DuplicateKeyException alreadyPersisted) {
            log.debug(
                    "alert already stored, ignoring the duplicate event-id={} vehicle-id={}",
                    alert.eventId(),
                    alert.vehicleId());
        }
    }

    private void upsertVehicleState(String vehicleId, VehicleState state) {
        Instant writtenAt = clock.instant();
        if (isUnknownVehicle(vehicleId)) {
            jdbcTemplate.update(INSERT_VEHICLE, vehicleId, Timestamp.from(writtenAt));
        }

        int updated = jdbcTemplate.update(
                UPDATE_LATEST_STATE,
                state.latitude(),
                state.longitude(),
                state.speed(),
                state.heading(),
                state.status().name(),
                Timestamp.from(state.lastUpdate()),
                Timestamp.from(writtenAt),
                vehicleId);
        if (updated == 0) {
            jdbcTemplate.update(
                    INSERT_LATEST_STATE,
                    vehicleId,
                    state.latitude(),
                    state.longitude(),
                    state.speed(),
                    state.heading(),
                    state.status().name(),
                    Timestamp.from(state.lastUpdate()),
                    Timestamp.from(writtenAt));
        }
    }

    private boolean isUnknownVehicle(String vehicleId) {
        List<Long> vehicle = jdbcTemplate.queryForList(SELECT_VEHICLE, Long.class, vehicleId);
        return vehicle.isEmpty();
    }

    /** The {@code data} document of the alert contract, kept verbatim as JSON. */
    private String metadata(AlertEvent alert) {
        try {
            return objectMapper.writeValueAsString(alert.data());
        } catch (JsonProcessingException failure) {
            // The document is built locally from contract types, so this is a defect rather
            // than an input problem. Failing the record is safe: the write is idempotent.
            throw new IllegalStateException("serializing the metadata of alert " + alert.eventId() + " failed", failure);
        }
    }
}

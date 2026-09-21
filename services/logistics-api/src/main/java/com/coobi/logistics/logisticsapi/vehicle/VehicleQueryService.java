package com.coobi.logistics.logisticsapi.vehicle;

import com.coobi.logistics.logisticsapi.web.ApiNotFoundException;
import com.coobi.logistics.logisticsapi.web.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The vehicle views of MVP-5.2.
 *
 * <p>Both endpoints answer from the latest-state read model, mapped to DTOs here: the
 * entities of the persistence layer never reach the response (MVP-5.2 acceptance), and the
 * order of a page is fixed by the service instead of by the caller, so paging is stable and
 * the index of the schema - {@code (status, last_update DESC)} - is the one that serves it.
 * Ties are broken by vehicle id, so a page boundary cannot drop or repeat a vehicle.
 */
@Service
@Transactional(readOnly = true)
public class VehicleQueryService {

    /** Largest page a caller may ask for, shared with the controller constraint. */
    public static final int MAX_PAGE_SIZE = 100;

    private static final Sort ORDER = Sort.by(
            Sort.Order.desc("lastUpdate"),
            Sort.Order.asc("vehicleId"));

    private final VehicleLatestStateRepository vehicles;

    public VehicleQueryService(VehicleLatestStateRepository vehicles) {
        this.vehicles = Objects.requireNonNull(vehicles, "vehicles must not be null");
    }

    /**
     * One page of the fleet, newest telemetry first.
     *
     * @param status status to filter by, or {@code null} for every status
     * @param page zero-based page index
     * @param size page size, never above {@link #MAX_PAGE_SIZE}
     * @return the page of the fleet as the API reports it
     */
    public PageResponse<VehicleResponse> page(VehicleStatus status, int page, int size) {
        Page<VehicleLatestState> found = vehicles.findAll(
                VehicleLatestStateRepository.hasStatus(status),
                PageRequest.of(page, size, ORDER));
        return PageResponse.of(found.map(VehicleQueryService::toResponse));
    }

    /**
     * The latest known state of one vehicle.
     *
     * @param vehicleId business identifier of the vehicle
     * @return the vehicle as the API reports it
     * @throws ApiNotFoundException when no telemetry of that vehicle has ever been accepted
     */
    public VehicleResponse byId(String vehicleId) {
        return vehicles.findById(vehicleId)
                .map(VehicleQueryService::toResponse)
                .orElseThrow(() -> new ApiNotFoundException("no vehicle with id '" + vehicleId + "'"));
    }

    /**
     * The event time of the newest telemetry stored.
     *
     * <p>It is where the live event stream (MVP-6.1) starts, so a stream carries the movement
     * that happens while it is open instead of replaying the fleet this paged endpoint serves.
     *
     * @return the newest {@code lastUpdate}, or empty when no telemetry has ever been stored
     */
    public Optional<Instant> liveEdge() {
        return vehicles.findFirstByOrderByLastUpdateDesc().map(VehicleLatestState::getLastUpdate);
    }

    /**
     * The newest vehicle states whose telemetry is more recent than one instant.
     *
     * @param lastUpdate event time to start after
     * @param limit maximum number of states to answer with; when more were updated, the newest
     *        ones are the ones returned, so a reader that streams them stays at the live edge
     * @return the states as the API reports them, newest first, at most {@code limit} of them
     */
    public List<VehicleResponse> newerThan(Instant lastUpdate, int limit) {
        return vehicles
                .findByLastUpdateGreaterThanOrderByLastUpdateDesc(lastUpdate, PageRequest.of(0, limit))
                .stream()
                .map(VehicleQueryService::toResponse)
                .toList();
    }

    private static VehicleResponse toResponse(VehicleLatestState state) {
        return new VehicleResponse(
                state.getVehicleId(),
                state.getLatitude(),
                state.getLongitude(),
                state.getSpeed(),
                state.getHeading(),
                state.getStatus(),
                state.getLastUpdate(),
                state.getUpdatedAt());
    }
}

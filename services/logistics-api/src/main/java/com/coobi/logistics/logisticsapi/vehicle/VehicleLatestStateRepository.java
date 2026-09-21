package com.coobi.logistics.logisticsapi.vehicle;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Read access to the latest state of every vehicle.
 *
 * <p>The repository is a reader: there is no save method of its own, and the service layer
 * opens its transactions read-only, so the API cannot mutate the read model the stream
 * processor owns.
 */
public interface VehicleLatestStateRepository
        extends JpaRepository<VehicleLatestState, String>, JpaSpecificationExecutor<VehicleLatestState> {

    /**
     * Number of vehicles whose latest reported status is the given one, counted by the
     * database instead of by loading the fleet.
     *
     * @param status status to count
     * @return number of vehicles in that status
     */
    long countByStatus(VehicleStatus status);

    /**
     * The optional {@code status} filter of the vehicle list.
     *
     * @param status status to keep, or {@code null} to keep every vehicle
     * @return specification that matches the filter
     */
    static Specification<VehicleLatestState> hasStatus(VehicleStatus status) {
        return (root, query, builder) -> status == null
                ? builder.conjunction()
                : builder.equal(root.get("status"), status);
    }
}

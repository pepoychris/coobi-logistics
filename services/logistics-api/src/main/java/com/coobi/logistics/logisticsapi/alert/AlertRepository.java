package com.coobi.logistics.logisticsapi.alert;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Read access to the stored alerts.
 *
 * <p>The three filters of MVP-5.3 are expressed as one specification: a filter that is
 * absent contributes no predicate, so the filters combine independently and any subset of
 * them can be used. The service layer normalises the values before they reach this class,
 * which keeps the rules of the API - a blank vehicle id means no filter - out of the query.
 */
public interface AlertRepository
        extends JpaRepository<AlertRecord, Long>, JpaSpecificationExecutor<AlertRecord> {

    /**
     * The optional filters of the alert list, combined with AND.
     *
     * @param vehicleId vehicle to keep, or {@code null} for every vehicle
     * @param type alert type to keep, or {@code null} for every type
     * @param severity severity to keep, or {@code null} for every severity
     * @return specification that matches the filters that were given
     */
    static Specification<AlertRecord> filters(String vehicleId, AlertType type, AlertSeverity severity) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (vehicleId != null) {
                predicates.add(builder.equal(root.get("vehicleId"), vehicleId));
            }
            if (type != null) {
                predicates.add(builder.equal(root.get("type"), type));
            }
            if (severity != null) {
                predicates.add(builder.equal(root.get("severity"), severity));
            }
            return predicates.isEmpty()
                    ? builder.conjunction()
                    : builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * The newest stored alert.
     *
     * <p>Its key is where the event stream of MVP-6.1 starts: the id is generated in order, so
     * "the newest key" is a position in the table that a stream can continue from without a
     * timestamp and without reading the rows it skipped.
     *
     * @return the alert with the highest key, or empty when no alert is stored
     */
    Optional<AlertRecord> findFirstByOrderByIdDesc();

    /**
     * The newest alerts stored after one key, newest first.
     *
     * @param id key to start after
     * @param pageable page request that bounds how many alerts are read
     * @return the alerts, newest first
     */
    List<AlertRecord> findByIdGreaterThanOrderByIdDesc(long id, Pageable pageable);
}

package com.coobi.logistics.logisticsapi.vehicle;

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
 * {@code GET /api/v1/vehicles} and {@code GET /api/v1/vehicles/{vehicleId}} (MVP-5.2).
 *
 * <p>The controller only translates HTTP into calls: it declares the query parameters, the
 * constraints the request has to satisfy, and the path variables. Invalid parameters are
 * rejected with {@code 400} by the validation of the parameters and by the error handler of
 * the API, and an unknown vehicle is reported by the service as {@code 404}.
 */
@RestController
@RequestMapping(path = "/api/v1/vehicles", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class VehicleController {

    private final VehicleQueryService vehicles;

    public VehicleController(VehicleQueryService vehicles) {
        this.vehicles = vehicles;
    }

    /**
     * One page of the fleet, newest telemetry first.
     *
     * @param status optional {@code MOVING} or {@code STOPPED} filter; omitted means no filter
     * @param page zero-based page index, {@code 0} by default
     * @param size page size, {@code 20} by default and at most {@value VehicleQueryService#MAX_PAGE_SIZE}
     * @return the page of the fleet
     */
    @GetMapping
    public PageResponse<VehicleResponse> list(
            @RequestParam(name = "status", required = false) VehicleStatus status,
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = "must be greater than or equal to 0") int page,
            @RequestParam(name = "size", defaultValue = "20")
            @Min(value = 1, message = "must be greater than or equal to 1")
            @Max(
                    value = VehicleQueryService.MAX_PAGE_SIZE,
                    message = "must be less than or equal to " + VehicleQueryService.MAX_PAGE_SIZE) int size) {
        return vehicles.page(status, page, size);
    }

    /**
     * The latest known state of one vehicle.
     *
     * @param vehicleId business identifier of the vehicle
     * @return the vehicle
     */
    @GetMapping("/{vehicleId}")
    public VehicleResponse byId(@PathVariable(name = "vehicleId") String vehicleId) {
        return vehicles.byId(vehicleId);
    }
}

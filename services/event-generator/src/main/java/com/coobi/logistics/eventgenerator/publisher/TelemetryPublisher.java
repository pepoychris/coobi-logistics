package com.coobi.logistics.eventgenerator.publisher;

import com.coobi.logistics.eventgenerator.event.VehicleLocationEvent;

/**
 * Destination of the generated telemetry, with the counters used to observe
 * throughput and failures.
 */
public interface TelemetryPublisher {

    /** Publishes one event without blocking the caller. */
    void publish(VehicleLocationEvent event);

    /** Events confirmed by the broker since startup. */
    long publishedCount();

    /** Events that could not be serialized or delivered since startup. */
    long failedCount();
}

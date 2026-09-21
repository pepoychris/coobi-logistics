package com.coobi.logistics.logisticsapi.statistics;

import java.util.Optional;

/**
 * The processed-event counters of the stream processor, as the statistics endpoint uses
 * them (MVP-5.4).
 *
 * <p>The interface exists so the statistics endpoint can be verified without a running
 * processor: the endpoint depends on the counters, not on the way they are read.
 */
public interface StreamProcessorMetrics {

    /**
     * Reads the counters the processor publishes.
     *
     * @return the counters, or empty when the processor cannot be reached or does not
     *         publish them; the endpoint reports that absence instead of inventing a number
     */
    Optional<ProcessedEvents> read();

    /**
     * Cumulative processed events of the topology, and the rate observed since the previous
     * read of this instance.
     *
     * @param total records the topology has processed since it started
     * @param perSecond average events per second between the two most recent reads, or
     *        {@code null} when no rate can be derived yet - the first read of this instance,
     *        or a counter that restarted with the processor
     */
    record ProcessedEvents(long total, Long perSecond) {
    }
}

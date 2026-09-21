package com.coobi.logistics.eventgenerator.publisher;

/**
 * Converts a target rate in events per second into a bounded batch per tick.
 *
 * <p>A fractional budget is carried across calls, so a target that is not a multiple of
 * the tick capacity is still met on average: 1,000 events/s at a 100 ms tick yields ten
 * events per tick, while 1,234 events/s alternates between 123 and 124.
 *
 * <p>The batch never exceeds {@code maxEventsPerTick}. Surplus budget is discarded
 * instead of being accumulated, so a slow tick cannot be followed by an unbounded burst.
 *
 * <p>Not thread-safe: one instance belongs to one publishing task.
 */
public final class PublishRatePlanner {

    private double budget;

    /**
     * @param targetEventsPerSecond configured target rate
     * @param tickIntervalMillis length of one publishing tick
     * @param maxEventsPerTick upper bound of a single batch
     * @return number of events to publish in this tick, never negative
     */
    public int planBatch(int targetEventsPerSecond, int tickIntervalMillis, int maxEventsPerTick) {
        if (targetEventsPerSecond < 0) {
            throw new IllegalArgumentException("targetEventsPerSecond must not be negative but was " + targetEventsPerSecond);
        }
        if (tickIntervalMillis <= 0) {
            throw new IllegalArgumentException("tickIntervalMillis must be greater than 0 but was " + tickIntervalMillis);
        }
        if (maxEventsPerTick < 1) {
            throw new IllegalArgumentException("maxEventsPerTick must be at least 1 but was " + maxEventsPerTick);
        }

        budget += ((double) targetEventsPerSecond) * tickIntervalMillis / 1000.0;
        int planned = (int) Math.floor(budget);
        if (planned > maxEventsPerTick) {
            planned = maxEventsPerTick;
        }
        budget -= planned;
        if (budget > maxEventsPerTick) {
            budget = maxEventsPerTick;
        }
        return planned;
    }

    /** Events carried over from the previous tick, for diagnostics. */
    public double pendingBudget() {
        return budget;
    }
}

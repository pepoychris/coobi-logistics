package com.coobi.logistics.logisticsapi.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Waiting for something a test cannot ask for directly.
 *
 * <p>A stream does its work on its own threads - a connection is drained by a virtual thread of
 * its own, and a lost socket is noticed by a write rather than by a call - so a test observes
 * the result instead of the call. The wait is bounded and says what it was waiting for, so a
 * failure names the condition rather than a timeout.
 */
public final class Await {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private Await() {
    }

    /**
     * Waits until a condition holds.
     *
     * @param condition what is being waited for, in the words of a failure message
     * @param ready the condition itself
     * @throws AssertionError when it does not hold within the timeout
     */
    public static void until(String condition, BooleanSupplier ready) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (ready.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + condition, interrupted);
            }
        }
        throw new AssertionError("timed out after " + TIMEOUT + " waiting for " + condition);
    }
}

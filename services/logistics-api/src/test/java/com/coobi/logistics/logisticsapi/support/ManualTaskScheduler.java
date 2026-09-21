package com.coobi.logistics.logisticsapi.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * A scheduler of a test: it never runs anything by itself.
 *
 * <p>The ticks of a stream are run by the test that asks for them, so a test about what a
 * stream does when nobody is connected does not have to wait for a real interval to pass, and
 * a test about a stream that keeps ticking does not become slower on a busy machine.
 */
public final class ManualTaskScheduler implements TaskScheduler {

    private final List<Tick> ticks = new ArrayList<>();
    private final AtomicInteger cancellations = new AtomicInteger();

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        Tick tick = new Tick(task, delay);
        ticks.add(tick);
        return tick;
    }

    /**
     * Runs every tick that is still scheduled, in the order it was scheduled.
     *
     * <p>A cancelled tick is skipped, exactly as a real scheduler would skip it.
     */
    public void runTicks() {
        new ArrayList<>(ticks).stream().filter(Tick::stillScheduled).forEach(Tick::run);
    }

    /** @return how many ticks the streams of the test have scheduled */
    public int scheduled() {
        return ticks.size();
    }

    /** @return how many scheduled ticks have been cancelled */
    public int cancelled() {
        return cancellations.get();
    }

    /** @return the interval the given tick was scheduled with, zero-based */
    public Duration intervalOf(int index) {
        return ticks.get(index).interval;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        throw new UnsupportedOperationException("the streams of this service schedule fixed delays");
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        throw new UnsupportedOperationException("the streams of this service schedule fixed delays");
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
        throw new UnsupportedOperationException("the streams of this service start right away");
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        throw new UnsupportedOperationException("the streams of this service schedule fixed delays");
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
        throw new UnsupportedOperationException("the streams of this service schedule fixed delays");
    }

    /** One scheduled tick, which the test can run or cancel. */
    private final class Tick implements ScheduledFuture<Object> {

        private final Runnable task;
        private final Duration interval;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Tick(Runnable task, Duration interval) {
            this.task = task;
            this.interval = interval;
        }

        private boolean stillScheduled() {
            return !cancelled.get();
        }

        private void run() {
            task.run();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(interval);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!cancelled.compareAndSet(false, true)) {
                return false;
            }
            cancellations.incrementAndGet();
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException("a tick never completes");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("a tick never completes");
        }
    }
}

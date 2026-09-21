package com.coobi.logistics.logisticsapi.stream;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The frames waiting to be read by one browser connection (MVP-6.1).
 *
 * <p>The buffer is bounded on purpose. A browser that stops reading - a suspended laptop, a
 * stalled network - must not be able to grow the memory of this service, so a frame that
 * arrives when the buffer is full replaces the oldest one instead of being appended. The
 * client sees a gap in the stream, and the service stays exactly as large as its
 * configuration says, which is the only bounded answer to a reader that fell behind.
 *
 * <p>The buffer belongs to one connection and is read by one thread, which is the pump of that
 * connection; a tick may offer to it while the pump is taking from it.
 */
final class FrameBuffer {

    private final BlockingQueue<StreamFrame> frames;

    /**
     * @param capacity frames to hold for one connection; never below one
     */
    FrameBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, was " + capacity);
        }
        this.frames = new ArrayBlockingQueue<>(capacity);
    }

    /**
     * Queues one frame for the connection.
     *
     * @param frame frame to send
     * @return whether the buffer was full, in which case its oldest frame was dropped to make
     *         room for this one
     */
    boolean offer(StreamFrame frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        if (frames.offer(frame)) {
            return false;
        }
        frames.poll();
        frames.offer(frame);
        return true;
    }

    /**
     * Takes the next frame, waiting for one to arrive.
     *
     * @param timeout how long to wait
     * @return the frame, or {@code null} when none arrived within the timeout, which is what
     *         tells the connection to send a heartbeat
     * @throws InterruptedException when the connection is being closed while waiting
     */
    StreamFrame poll(Duration timeout) throws InterruptedException {
        return frames.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}

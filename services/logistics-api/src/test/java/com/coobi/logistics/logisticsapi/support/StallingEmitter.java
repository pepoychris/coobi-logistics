package com.coobi.logistics.logisticsapi.support;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * A browser connection that stopped reading.
 *
 * <p>The first frame - the one that opens the connection - is accepted, and every following
 * write blocks until the test releases it, which is what a browser whose network stalled looks
 * like to the pump that writes to it.
 */
public final class StallingEmitter extends SseEmitter {

    private final List<String> frames = new ArrayList<>();
    private final AtomicInteger writes = new AtomicInteger();
    private final CountDownLatch stalled = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    @Override
    public void send(Object object, MediaType mediaType) throws IOException {
        record(String.valueOf(object));
    }

    @Override
    public void send(SseEmitter.SseEventBuilder builder) throws IOException {
        record(SseEventText.of(builder));
    }

    private void record(String frame) throws IOException {
        synchronized (frames) {
            frames.add(frame);
        }
        if (writes.incrementAndGet() > 1) {
            stalled.countDown();
            try {
                if (!released.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("the stalled connection was never released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("the stalled connection was interrupted", interrupted);
            }
        }
    }

    /**
     * @return whether the connection is now blocked in a write, within five seconds
     */
    public boolean awaitStalled() throws InterruptedException {
        return stalled.await(5, TimeUnit.SECONDS);
    }

    /** @return the frames the connection accepted, in order, before and during the stall */
    public List<String> frames() {
        synchronized (frames) {
            return new ArrayList<>(frames);
        }
    }

    /** Lets the blocked write finish, so the connection can be closed. */
    public void release() {
        released.countDown();
    }
}

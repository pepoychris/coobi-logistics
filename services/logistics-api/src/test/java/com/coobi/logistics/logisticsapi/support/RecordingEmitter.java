package com.coobi.logistics.logisticsapi.support;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * A browser connection of a test.
 *
 * <p>It records what a stream sends instead of writing it to a socket, and it can be made to
 * fail on demand - which is how the tests reproduce a browser that closed the page without
 * waiting for the stream to find out.
 */
public final class RecordingEmitter extends SseEmitter {

    /** The frames that were sent, in order. */
    private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();

    /** Frames this connection accepts before it starts failing, or {@code -1} for never. */
    private final AtomicInteger budget;

    /** Set while this connection is meant to be a browser that went away. */
    private final AtomicBoolean failing = new AtomicBoolean();

    public RecordingEmitter() {
        this(-1);
    }

    /**
     * @param budget frames to accept before failing, or {@code -1} to accept every frame
     */
    public RecordingEmitter(int budget) {
        this.budget = new AtomicInteger(budget);
    }

    @Override
    public void send(Object object, MediaType mediaType) throws IOException {
        if (gone()) {
            throw new IOException("the browser is gone");
        }
        frames.add(String.valueOf(object));
    }

    @Override
    public void send(SseEmitter.SseEventBuilder builder) throws IOException {
        if (gone()) {
            throw new IOException("the browser is gone");
        }
        frames.add(SseEventText.of(builder));
    }

    private boolean gone() {
        if (failing.get() || (budget.get() >= 0 && budget.getAndDecrement() <= 0)) {
            failing.set(true);
            return true;
        }
        return false;
    }

    /** Makes every following send fail, which is how a connection that was closed behaves. */
    public void fail() {
        failing.set(true);
    }

    /**
     * @param timeout how long to wait for a frame
     * @return the next frame, or {@code null} when none arrived within the timeout
     */
    public String nextFrame(Duration timeout) throws InterruptedException {
        return frames.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** @return the frames sent so far, in order */
    public List<String> frames() {
        return new ArrayList<>(frames);
    }
}

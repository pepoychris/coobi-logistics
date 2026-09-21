package com.coobi.logistics.logisticsapi.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * MVP-6.1: the buffer of one browser connection is bounded, and a reader that fell behind
 * loses its oldest frames instead of the service its memory.
 */
class FrameBufferTest {

    private static final Duration WAIT = Duration.ofMillis(50);

    private static final StreamFrame FIRST = StreamFrame.event("alert", "{\"sequence\":1}");
    private static final StreamFrame SECOND = StreamFrame.event("alert", "{\"sequence\":2}");
    private static final StreamFrame THIRD = StreamFrame.event("alert", "{\"sequence\":3}");

    @Test
    void holdsTheFramesOfOneConnectionInOrder() throws Exception {
        FrameBuffer buffer = new FrameBuffer(4);

        assertThat(buffer.offer(FIRST)).isFalse();
        assertThat(buffer.offer(SECOND)).isFalse();

        assertThat(buffer.poll(WAIT)).isSameAs(FIRST);
        assertThat(buffer.poll(WAIT)).isSameAs(SECOND);
    }

    @Test
    void dropsTheOldestFrameWhenItIsFull() throws Exception {
        FrameBuffer buffer = new FrameBuffer(2);

        buffer.offer(FIRST);
        buffer.offer(SECOND);
        boolean dropped = buffer.offer(THIRD);

        assertThat(dropped).as("the buffer was full, so a frame was lost").isTrue();
        assertThat(buffer.poll(WAIT)).as("the oldest frame is the one that goes").isSameAs(SECOND);
        assertThat(buffer.poll(WAIT)).isSameAs(THIRD);
    }

    @Test
    void answersNothingWhenNoFrameArrives() throws Exception {
        FrameBuffer buffer = new FrameBuffer(1);

        assertThat(buffer.poll(WAIT)).isNull();
    }

    @Test
    void refusesToBeUnboundedOrUnusable() {
        assertThat(new FrameBuffer(1)).isNotNull();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new FrameBuffer(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }
}

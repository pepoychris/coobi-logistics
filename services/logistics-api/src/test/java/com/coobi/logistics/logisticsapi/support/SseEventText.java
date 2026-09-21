package com.coobi.logistics.logisticsapi.support;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The text of a frame, built by the same builder that sends it.
 *
 * <p>A connection of a test is an emitter, and the frames a stream hands to it are SSE builders:
 * rendering them here means the tests assert what the framework writes instead of a copy of it.
 */
public final class SseEventText {

    private SseEventText() {
    }

    /**
     * @param builder builder of one frame
     * @return the frame as a client reads it
     */
    public static String of(SseEmitter.SseEventBuilder builder) {
        StringBuilder text = new StringBuilder();
        builder.build().forEach(part -> text.append(part.getData()));
        return text.toString();
    }
}

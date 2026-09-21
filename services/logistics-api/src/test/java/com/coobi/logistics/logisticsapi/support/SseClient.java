package com.coobi.logistics.logisticsapi.support;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A browser connection of a test, over a real socket.
 *
 * <p>It exists because the event streams are HTTP responses that never end: a test has to read
 * the response as it is written, with deadlines, rather than wait for a body that is complete.
 * The frames are read as lines of the SSE wire format and handed back whole, which is what makes
 * an assertion about a stream read like an assertion about the protocol.
 */
public final class SseClient implements AutoCloseable {

    private final HttpClient http = HttpClient.newHttpClient();
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final HttpResponse<java.io.InputStream> response;
    private final Thread reader;

    /**
     * Opens a connection and lets it stream.
     *
     * @param uri address of the stream endpoint
     * @throws IOException when the request cannot be sent
     * @throws InterruptedException when the test is interrupted while the response headers
     *         are being awaited
     */
    public SseClient(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        this.response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        this.reader = Thread.ofVirtual().name("sse-client").start(this::read);
    }

    /**
     * Reads the connection, line by line, until the test closes it.
     *
     * <p>The lines are read here rather than left to a lazy stream: a stream of a response that
     * never ends has to be pulled by a thread that does nothing else, and the test has to see
     * every line as it arrives.
     */
    private void read() {
        try (BufferedReader connection = new BufferedReader(new InputStreamReader(response.body()))) {
            String line;
            while ((line = connection.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException | RuntimeException closed) {
            // The test closed the connection, which is how a connection ends here.
        }
    }

    /** @return the status of the response, which the server sends before the first event */
    public int status() {
        return response.statusCode();
    }

    /**
     * @param name header name
     * @return the value of the header, or an empty string when it was not sent
     */
    public String header(String name) {
        return response.headers().firstValue(name).orElse("");
    }

    /**
     * Reads one frame.
     *
     * @param timeout how long to wait for it
     * @return the frame, with its lines joined by newlines, or {@code null} when none arrived
     *         within the timeout
     */
    public String awaitFrame(Duration timeout) throws InterruptedException {
        StringBuilder frame = new StringBuilder();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return frame.isEmpty() ? null : frame.toString();
            }
            String line = lines.poll(left, TimeUnit.NANOSECONDS);
            if (line == null) {
                return frame.isEmpty() ? null : frame.toString();
            }
            if (line.isEmpty()) {
                if (!frame.isEmpty()) {
                    return frame.toString();
                }
                continue;
            }
            if (!frame.isEmpty()) {
                frame.append('\n');
            }
            frame.append(line);
        }
    }

    /**
     * Reads events of one name, skipping the frames that carry something else - the opening of
     * the connection and its heartbeats are not events.
     *
     * @param name name of the event to wait for
     * @param timeout how long to wait for it
     * @return the payload of the event, as its {@code data:} lines were sent
     */
    public String awaitEvent(String name, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return null;
            }
            String frame = awaitFrame(Duration.ofNanos(left));
            if (frame == null) {
                return null;
            }
            if (is(frame, name)) {
                return payload(frame);
            }
        }
    }

    /**
     * Collects every event of one name that arrives within a window.
     *
     * @param name name of the event to collect
     * @param window how long to keep reading
     * @return the payloads of the events that arrived, in order
     */
    public List<String> collect(String name, Duration window) throws InterruptedException {
        List<String> payloads = new ArrayList<>();
        long deadline = System.nanoTime() + window.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return payloads;
            }
            String frame = awaitFrame(Duration.ofNanos(left));
            if (frame == null) {
                return payloads;
            }
            if (is(frame, name)) {
                payloads.add(payload(frame));
            }
        }
    }

    /** Closes the connection, as a browser that closed its page does. */
    @Override
    public void close() {
        try {
            response.body().close();
        } catch (IOException | RuntimeException alreadyClosed) {
            // Closing twice is not a failure of the test.
        }
        reader.interrupt();
        http.close();
    }

    /**
     * Whether a frame is one event of the given name.
     *
     * <p>The name is compared as the Server-Sent Events format writes it - one field of the
     * frame, with no space after the colon - so a stream that stopped naming its events the
     * documented way fails here instead of being read as if it still did.
     */
    private static boolean is(String frame, String name) {
        return frame.equals("event:" + name) || frame.startsWith("event:" + name + "\n");
    }

    /** Strips the {@code data:} prefix of every line of a frame. */
    private static String payload(String frame) {
        StringBuilder data = new StringBuilder();
        frame.lines().filter(line -> line.startsWith("data:")).forEach(line -> {
            if (!data.isEmpty()) {
                data.append('\n');
            }
            String value = line.substring("data:".length());
            data.append(value.startsWith(" ") ? value.substring(1) : value);
        });
        return data.toString();
    }
}

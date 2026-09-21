package com.coobi.logistics.logisticsapi.stream;

/**
 * Raised when a stream already serves as many browsers as it is configured to serve.
 *
 * <p>Refusing a connection is the bounded answer: accepting it would either hold unbounded
 * per-connection memory or slow down the service for the browsers that are already connected.
 * The API reports it as {@code 503}, and a browser may try again - its connection is not
 * broken, it was never opened.
 */
public class StreamCapacityExceededException extends RuntimeException {

    private final String stream;
    private final int maxSubscribers;

    /**
     * @param stream name of the stream, as its endpoint and its metrics call it
     * @param maxSubscribers configured maximum of concurrent browser connections
     */
    public StreamCapacityExceededException(String stream, int maxSubscribers) {
        super("the '" + stream + "' stream already serves its maximum of " + maxSubscribers
                + " browser connections");
        this.stream = stream;
        this.maxSubscribers = maxSubscribers;
    }

    public String stream() {
        return stream;
    }

    public int maxSubscribers() {
        return maxSubscribers;
    }
}

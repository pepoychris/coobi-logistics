package com.coobi.logistics.logisticsapi.web;

/**
 * A requested resource does not exist, which the API answers with {@code 404}.
 *
 * <p>The message is the one the client reads in the problem detail, so it names the
 * missing resource instead of restating the status code.
 */
public class ApiNotFoundException extends RuntimeException {

    public ApiNotFoundException(String message) {
        super(message);
    }
}

package com.coobi.logistics.logisticsapi.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stored {@code AlertData} of an alert, read as JSON.
 *
 * <p>The column is {@code jsonb}, so its text is valid JSON and a response embeds the
 * document directly - a client reads the measured value and the threshold that produced the
 * alert without parsing a string of JSON twice. The defensive branch exists anyway: one
 * unreadable row must not fail the page it appears in, so such a row is reported as the text
 * it holds and logged, while the alerts beside it are answered normally.
 */
class AlertMetadata {

    private static final Logger log = LoggerFactory.getLogger(AlertMetadata.class);

    private final ObjectMapper objectMapper;

    AlertMetadata(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * Reads the metadata of one alert.
     *
     * @param alertId key of the alert, used to name the row if its metadata cannot be read
     * @param metadata stored {@code AlertData}
     * @return the metadata as JSON
     */
    JsonNode read(Long alertId, String metadata) {
        try {
            return objectMapper.readTree(metadata);
        } catch (JsonProcessingException malformed) {
            log.warn("alert metadata is not readable JSON, reported as text alert-id={}", alertId);
            return TextNode.valueOf(metadata);
        }
    }
}

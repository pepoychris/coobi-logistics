package com.coobi.logistics.logisticsapi.web;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * One page of a collection endpoint, as returned by {@code /api/v1/vehicles} and
 * {@code /api/v1/alerts}.
 *
 * <p>The shape is deliberately explicit instead of the serialized {@code Page} of Spring
 * Data: the response of this API is a contract with the browser, and the internal
 * pagination object of a persistence library is not one.
 *
 * @param content the page, already sorted by the endpoint
 * @param page zero-based index of this page
 * @param size maximum number of elements the caller asked for
 * @param totalElements elements matching the filter across every page
 * @param totalPages pages the matching elements are spread over
 * @param first whether this is the first page
 * @param last whether this is the last page
 * @param <T> element type of the endpoint
 */
@JsonPropertyOrder({"content", "page", "size", "totalElements", "totalPages", "first", "last"})
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public PageResponse {
        content = List.copyOf(content);
    }

    /**
     * Copies a page of the persistence layer into the response contract.
     *
     * @param page the page returned by a repository, already mapped to response DTOs
     * @param <T> element type of the endpoint
     * @return the page as the API reports it
     */
    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}

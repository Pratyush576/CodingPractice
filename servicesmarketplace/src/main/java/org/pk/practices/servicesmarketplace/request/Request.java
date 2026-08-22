package org.pk.practices.servicesmarketplace.request;

import java.time.Instant;

/** DESIGN.md §3 Domain Model. {@code answers} is the raw JSON text against {@code Category.questionnaireSchema}. */
public record Request(
        String requestId,
        String customerId,
        String categoryId,
        String answers,
        double locationLat,
        double locationLng,
        String desiredTiming,
        RequestStatus status,
        String hiredQuoteId,
        Instant createdAt
) {
    public Request withStatus(RequestStatus newStatus, String hiredQuoteId) {
        return new Request(requestId, customerId, categoryId, answers, locationLat, locationLng,
                desiredTiming, newStatus, hiredQuoteId, createdAt);
    }
}

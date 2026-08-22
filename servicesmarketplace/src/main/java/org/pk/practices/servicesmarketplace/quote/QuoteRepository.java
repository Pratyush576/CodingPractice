package org.pk.practices.servicesmarketplace.quote;

import java.util.List;
import java.util.Optional;

public interface QuoteRepository {
    void insert(Quote quote);
    Optional<Quote> find(String quoteId);
    Optional<Quote> findByLead(String leadId);

    /** Joins through {@code leads} — {@code quotes} has no {@code request_id} column of its own. */
    List<Quote> findByRequest(String requestId);

    void updateStatus(String quoteId, QuoteStatus status);

    /** All other {@code PENDING} quotes on the same Request as {@code quoteId}, marked {@code DECLINED} — DESIGN.md §4.4's hire fan-out. */
    void declineOthersOnSameRequest(String winningQuoteId, String requestId);
}

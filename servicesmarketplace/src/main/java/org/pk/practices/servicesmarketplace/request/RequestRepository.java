package org.pk.practices.servicesmarketplace.request;

import java.util.List;
import java.util.Optional;

public interface RequestRepository {
    void insert(Request request);
    Optional<Request> find(String requestId);
    List<Request> findByCustomer(String customerId);

    /** {@code UPDATE requests SET status='HIRED', hired_quote_id=? WHERE request_id=? AND status='OPEN'} — DESIGN.md §4.4's hire CAS. */
    boolean hire(String requestId, String quoteId);
}

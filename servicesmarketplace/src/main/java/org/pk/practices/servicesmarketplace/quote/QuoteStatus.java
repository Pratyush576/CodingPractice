package org.pk.practices.servicesmarketplace.quote;

/** DESIGN.md §3 — {@code PENDING -> ACCEPTED} is the hire CAS (§4.4); every other open Quote on the same Request goes to DECLINED. */
public enum QuoteStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    EXPIRED
}

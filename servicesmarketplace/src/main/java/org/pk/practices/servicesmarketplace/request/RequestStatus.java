package org.pk.practices.servicesmarketplace.request;

/** DESIGN.md §3 — {@code OPEN -> HIRED} is the hire CAS (§4.4); {@code HIRED -> COMPLETED} arrives in Phase 4. */
public enum RequestStatus {
    OPEN,
    HIRED,
    COMPLETED,
    CANCELLED
}

package org.pk.practices.servicesmarketplace.lead;

/** DESIGN.md §3 — {@code DELIVERED -> UNLOCKED} is the credit-deduction idempotency guarantee (§4.3). */
public enum LeadStatus {
    DELIVERED,
    UNLOCKED,
    QUOTED,
    WON,
    LOST,
    EXPIRED
}

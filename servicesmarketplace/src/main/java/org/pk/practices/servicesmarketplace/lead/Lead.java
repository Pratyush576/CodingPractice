package org.pk.practices.servicesmarketplace.lead;

import java.time.Instant;

/** DESIGN.md §3 Domain Model — a Request as seen from one matched Pro's side. */
public record Lead(
        String leadId,
        String requestId,
        String proId,
        LeadStatus status,
        double creditCost,
        Instant createdAt,
        Instant unlockedAt
) {
    public Lead withStatus(LeadStatus newStatus, Instant unlockedAt) {
        return new Lead(leadId, requestId, proId, newStatus, creditCost, createdAt, unlockedAt);
    }
}

package org.pk.practices.cabreservation.payment;

import java.time.Instant;

/** Unused until Phase 2. DESIGN.md §3/§4.7 — the Accounts Payable side; {@code amount} is fare minus platform commission. */
public record Payout(String payoutId, String tripId, String driverId, double amount, PayoutStatus status, String providerReference, Instant createdAt) {}

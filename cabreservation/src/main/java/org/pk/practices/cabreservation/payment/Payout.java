package org.pk.practices.cabreservation.payment;

import java.time.Instant;

/** DESIGN.md §3/§4.7 — the Accounts Payable side; {@code amount} is fareFinal minus platform commission, {@code tripId} unique for idempotency (enforced by {@code payouts.trip_id UNIQUE}). */
public record Payout(String payoutId, String tripId, String driverId, double amount, PayoutStatus status, String providerReference, Instant createdAt) {}

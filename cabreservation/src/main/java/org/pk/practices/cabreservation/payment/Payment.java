package org.pk.practices.cabreservation.payment;

import java.time.Instant;

/** Unused until Phase 2. DESIGN.md §3/§4.7 — the Accounts Receivable side; {@code tripId} is unique for idempotency. */
public record Payment(String paymentId, String tripId, double amount, PaymentStatus status, String gatewayReference, Instant createdAt) {}

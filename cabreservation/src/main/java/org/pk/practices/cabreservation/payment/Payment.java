package org.pk.practices.cabreservation.payment;

import java.time.Instant;

/** DESIGN.md §3/§4.7 — the Accounts Receivable side; {@code tripId} is unique for idempotency, enforced by {@code payments.trip_id UNIQUE}, not just this field's javadoc. */
public record Payment(String paymentId, String tripId, double amount, PaymentStatus status, String gatewayReference, Instant createdAt) {}

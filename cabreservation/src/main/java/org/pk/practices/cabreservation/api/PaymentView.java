package org.pk.practices.cabreservation.api;

import java.time.Instant;

/** A rider's own charge history line item — one per completed, charged trip. {@code fareFinal} matches {@code amount} today (no tax/tip line items yet), included for symmetry with {@link PayoutView}. */
public record PaymentView(String paymentId, String tripId, double amount, String status, String gatewayReference, Instant createdAt, Double fareFinal) {}

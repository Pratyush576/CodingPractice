package org.pk.practices.cabreservation.api;

import java.time.Instant;

/** A driver's own earnings line item — one per completed trip. {@code fareFinal} is included so a driver can see the commission implied (fareFinal − amount), not just the payout amount in isolation. */
public record PayoutView(String payoutId, String tripId, double amount, String status, String providerReference, Instant createdAt, Double fareFinal) {}

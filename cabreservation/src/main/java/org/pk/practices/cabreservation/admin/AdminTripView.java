package org.pk.practices.cabreservation.admin;

import java.time.Instant;

/**
 * One row of the admin's per-request financial detail view
 * ({@code GET /v1/admin/trips}) — a trip plus its counterparty names and
 * settlement outcome, if any. {@code paymentStatus}/{@code payoutStatus}
 * are {@code null} until {@code PaymentService}/{@code PayoutService} have
 * actually reacted to that trip's {@code TRIP_COMPLETED} event — not every
 * trip has one, and most never will (only completed trips do).
 */
public record AdminTripView(
        String tripId,
        String status,
        String riderName,
        String driverName,
        Instant createdAt,
        Double fareEstimate,
        Double fareFinal,
        String paymentStatus,
        Double paymentAmount,
        String payoutStatus,
        Double payoutAmount
) {}

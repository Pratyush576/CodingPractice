package org.pk.practices.cabreservation.trip;

import java.time.Instant;

/**
 * DESIGN.md §3 — the aggregate everything else hangs off. {@code fareEstimate}
 * is computed by {@code PricingStrategy} at request time; {@code fareFinal}
 * is computed at completion using the actual {@code startedAt}..completion
 * duration (Phase 2's real two-step estimate()/confirm() shape — no GPS
 * breadcrumb tracking, so distance is the same estimate both times, but
 * duration is genuinely observed, not guessed, the second time).
 * {@code version} backs the optimistic-concurrency CAS in
 * {@link TripRepository#compareAndSetStatus}.
 */
public record Trip(
        String tripId,
        String riderId,
        String driverId,
        TripStatus status,
        double pickupLat,
        double pickupLng,
        double dropoffLat,
        double dropoffLng,
        String offeredDriverId,
        Instant offerExpiresAt,
        Double fareEstimate,
        Double fareFinal,
        long version,
        Instant createdAt,
        Instant matchedAt,
        Instant startedAt,
        Instant completedAt
) {
    public Trip withStatus(TripStatus newStatus) {
        return new Trip(tripId, riderId, driverId, newStatus, pickupLat, pickupLng, dropoffLat, dropoffLng,
                offeredDriverId, offerExpiresAt, fareEstimate, fareFinal, version, createdAt, matchedAt, startedAt, completedAt);
    }
}

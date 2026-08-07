package org.pk.practices.cabreservation.trip;

import java.time.Instant;

/**
 * DESIGN.md §3 — the aggregate everything else hangs off. {@code fareEstimate}/
 * {@code fareFinal} stay null through Phase 1 (fare-sequencing decision —
 * Phase 2 restores §4.1's real two-step estimate()/confirm() shape).
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
        Instant completedAt
) {
    public Trip withStatus(TripStatus newStatus) {
        return new Trip(tripId, riderId, driverId, newStatus, pickupLat, pickupLng, dropoffLat, dropoffLng,
                offeredDriverId, offerExpiresAt, fareEstimate, fareFinal, version, createdAt, matchedAt, completedAt);
    }
}

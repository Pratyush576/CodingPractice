package org.pk.practices.cabreservation.api;

import org.pk.practices.cabreservation.trip.Trip;
import org.pk.practices.cabreservation.trip.TripStatus;

import java.time.Instant;

/**
 * A {@link Trip} plus the counterpart's name/rating/vehicle — this is what
 * actually answers "driver should see rider details, rider should see
 * driver details," since the raw {@code Trip} record only carries bare IDs.
 * {@code rider} is always known; {@code driver} is null until a driver has
 * actually accepted. {@code offeredDriver} covers the gap during
 * {@code MATCHING}: an offer is outstanding (not yet accepted) but the rider
 * still deserves to see who it went to and their cab, not just a bare
 * {@code offeredDriverId}.
 */
public record TripView(
        String tripId,
        TripStatus status,
        PartyInfo rider,
        PartyInfo driver,
        double pickupLat,
        double pickupLng,
        double dropoffLat,
        double dropoffLng,
        String offeredDriverId,
        PartyInfo offeredDriver,
        Instant offerExpiresAt,
        Double fareEstimate,
        Double fareFinal,
        Instant createdAt,
        Instant matchedAt,
        Instant completedAt
) {
    public static TripView of(Trip trip, PartyInfo rider, PartyInfo driver, PartyInfo offeredDriver) {
        return new TripView(trip.tripId(), trip.status(), rider, driver, trip.pickupLat(), trip.pickupLng(),
                trip.dropoffLat(), trip.dropoffLng(), trip.offeredDriverId(), offeredDriver, trip.offerExpiresAt(),
                trip.fareEstimate(), trip.fareFinal(), trip.createdAt(), trip.matchedAt(), trip.completedAt());
    }
}

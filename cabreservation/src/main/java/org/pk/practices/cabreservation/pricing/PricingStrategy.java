package org.pk.practices.cabreservation.pricing;

import java.time.Duration;

/**
 * DESIGN.md §4.6 — {@code base + distance×rate + time×rate}, kept as an
 * interface specifically so a surge multiplier or a different product
 * line's rate card can be swapped in later without callers (TripService)
 * changing at all.
 */
public interface PricingStrategy {
    /** Computed at trip request time, before the ride happens — distance is known, time is a guess. */
    double estimate(double pickupLat, double pickupLng, double dropoffLat, double dropoffLng);

    /**
     * Computed at trip completion. This build has no GPS-breadcrumb route
     * tracking, so distance is still the same pickup→dropoff estimate, but
     * {@code actualDuration} is genuinely observed (started→completed), not
     * guessed — that's the one real input the estimate couldn't have had.
     */
    double finalFare(double pickupLat, double pickupLng, double dropoffLat, double dropoffLng, Duration actualDuration);
}

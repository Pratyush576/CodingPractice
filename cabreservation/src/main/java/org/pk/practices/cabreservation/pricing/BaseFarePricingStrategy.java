package org.pk.practices.cabreservation.pricing;

import java.time.Duration;

/**
 * DESIGN.md §4.6's chosen formula, base + distance + time, nothing else —
 * surge/promotions/product-line rate cards are explicitly out of scope
 * (§11). Distance is great-circle (haversine) between pickup and dropoff,
 * not a road-network route — this build has no backend routing provider
 * (see DESIGN.md §4.5's Implementation Status), so it's a deliberate
 * simplification, consistently applied at both estimate and completion.
 */
public class BaseFarePricingStrategy implements PricingStrategy {

    private static final double BASE_FARE = 2.50;
    private static final double RATE_PER_KM = 1.50;
    private static final double RATE_PER_MINUTE = 0.25;
    private static final double ASSUMED_AVERAGE_SPEED_KMH = 30.0;
    private static final double EARTH_RADIUS_KM = 6371.0;

    @Override
    public double estimate(double pickupLat, double pickupLng, double dropoffLat, double dropoffLng) {
        double distanceKm = haversineKm(pickupLat, pickupLng, dropoffLat, dropoffLng);
        double estimatedMinutes = (distanceKm / ASSUMED_AVERAGE_SPEED_KMH) * 60.0;
        return fare(distanceKm, estimatedMinutes);
    }

    @Override
    public double finalFare(double pickupLat, double pickupLng, double dropoffLat, double dropoffLng, Duration actualDuration) {
        double distanceKm = haversineKm(pickupLat, pickupLng, dropoffLat, dropoffLng);
        // Floor of 1 minute — a near-instant test/demo completion shouldn't produce a near-zero time charge.
        double actualMinutes = Math.max(1.0, actualDuration.toSeconds() / 60.0);
        return fare(distanceKm, actualMinutes);
    }

    private static double fare(double distanceKm, double minutes) {
        double total = BASE_FARE + distanceKm * RATE_PER_KM + minutes * RATE_PER_MINUTE;
        return Math.round(total * 100.0) / 100.0;
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}

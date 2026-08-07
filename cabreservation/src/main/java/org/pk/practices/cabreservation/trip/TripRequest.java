package org.pk.practices.cabreservation.trip;

/**
 * The input DTO for {@code POST /v1/trips}. DESIGN.md §3 models a separate
 * durable {@code TripRequest} entity that "becomes a Trip once matched" —
 * this build collapses that into inserting a {@link Trip} directly with
 * {@code status=REQUESTED}, since nothing else in Phase 1 needs the
 * pre-Trip request to be independently queryable.
 */
public record TripRequest(double pickupLat, double pickupLng, double dropoffLat, double dropoffLng) {}

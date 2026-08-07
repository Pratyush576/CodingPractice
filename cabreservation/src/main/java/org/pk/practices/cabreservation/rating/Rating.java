package org.pk.practices.cabreservation.rating;

/** Unused until Phase 4. DESIGN.md §3/§4.8 — two rows per trip (rider→driver, driver→rider). */
public record Rating(String tripId, String raterId, String rateeId, int score, String comment) {}

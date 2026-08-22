package org.pk.practices.servicesmarketplace.pro;

import java.time.Instant;

/**
 * DESIGN.md §3 Domain Model — one row per Pro in Phase 1 (single-category
 * Pros, a documented simplification). {@code serviceAreaLat/Lng/RadiusKm} is
 * what {@code ProRepository.findMatchingProfiles} matches against — a plain
 * Postgres haversine query, not a live geo index (see the phased
 * implementation plan's Context section for why).
 */
public record ProProfile(
        String proId,
        String categoryId,
        double serviceAreaLat,
        double serviceAreaLng,
        double serviceAreaRadiusKm,
        Double startingPrice,
        Double minBudget,
        String maxJobSize,
        Instant updatedAt
) {}

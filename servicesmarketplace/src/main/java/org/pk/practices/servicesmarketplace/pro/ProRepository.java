package org.pk.practices.servicesmarketplace.pro;

import java.util.List;
import java.util.Optional;

public interface ProRepository {
    /** Inserts the pro row and its profile row in one transaction (pro_profiles.pro_id is a FK). */
    void insert(Pro pro, ProProfile profile);
    Optional<Pro> findById(String proId);
    Optional<Pro> findByEmail(String email);
    Optional<ProProfile> findProfileByProId(String proId);
    void updateProfile(ProProfile profile);

    /**
     * DESIGN.md §4.2's matching query — the haversine-in-SQL alternative to a
     * live Redis geo index (see the phased implementation plan's Context
     * section for why: Pro service areas are static, not a live-tracking
     * problem). Ordered by rating, capped at 5 — DESIGN.md §11's
     * max-Pros-per-Lead decision.
     */
    List<ProProfile> findMatchingProfiles(String categoryId, double lat, double lng);
}

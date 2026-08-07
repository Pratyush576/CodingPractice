package org.pk.practices.cabreservation.geo;

import java.util.List;

/**
 * DESIGN.md §4.2/§12 — pluggable geospatial backend. {@code regionCellId} is
 * an H3 cell (computed by {@link H3CellResolver}) used purely as a shard
 * key; the actual proximity query is delegated to the backing store.
 */
public interface GeoIndex {
    void upsertDriver(String regionCellId, String driverId, double lat, double lng);
    void removeDriver(String regionCellId, String driverId);
    List<NearbyDriver> findNearby(String regionCellId, double lat, double lng, double radiusKm, int maxResults);
}

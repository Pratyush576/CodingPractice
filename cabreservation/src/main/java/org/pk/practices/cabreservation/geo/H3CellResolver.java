package org.pk.practices.cabreservation.geo;

import com.uber.h3core.H3Core;

import java.io.IOException;

/**
 * DESIGN.md §4.2 — H3's job here is purely "which shard does this
 * coordinate belong to." Radius *expansion* is handled by retrying
 * GeoIndex.findNearby with a larger radius (Redis GEOSEARCH's native
 * parameter), not by walking H3 neighbor rings — a documented Phase 1
 * simplification, not a redesign.
 */
public class H3CellResolver {

    /** Resolution 4 ≈ city-sized cells (~1770 km² average) — coarse enough that a region rarely needs a cross-cell fan-out for a single ride. */
    private static final int REGION_RESOLUTION = 4;

    private final H3Core h3;

    public H3CellResolver() {
        try {
            this.h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize H3Core", e);
        }
    }

    public String regionCellId(double lat, double lng) {
        long cell = h3.latLngToCell(lat, lng, REGION_RESOLUTION);
        return h3.h3ToString(cell);
    }
}

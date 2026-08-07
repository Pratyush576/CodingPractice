package org.pk.practices.cabreservation.driver;

import java.time.Instant;

/**
 * DESIGN.md §3 Domain Model. {@code version} backs the optimistic-concurrency
 * CAS in {@link DriverRepository#compareAndSetStatus} — DESIGN.md §4.3's fix
 * for the double-dispatch race.
 */
public record Driver(
        String driverId,
        String name,
        String email,
        String passwordHash,
        String vehicleId,
        DriverStatus status,
        Double rating,
        Double lastLat,
        Double lastLng,
        Instant lastPingAt,
        long version,
        Instant createdAt,
        Instant updatedAt
) {}

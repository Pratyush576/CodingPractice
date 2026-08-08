package org.pk.practices.cabreservation.driver;

import org.pk.practices.cabreservation.common.ValidationException;

import java.util.List;

/**
 * The fixed palette a driver picks from for how their car marker renders on
 * a rider's map — purely cosmetic, not a domain concept DESIGN.md itself
 * defines. Centralized here so registration and the later "update my car
 * icon" endpoint validate against the exact same set.
 */
public enum CarIcon {
    BLUE, RED, GREEN, GOLD, PURPLE;

    /** Defaults to BLUE when blank (registration's original behavior); rejects anything not in the palette. */
    public static CarIcon parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return BLUE;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(List.of("carIcon must be one of " + List.of(values())));
        }
    }
}

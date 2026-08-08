package org.pk.practices.cabreservation.driver;

/**
 * DESIGN.md §3 — one driver ↔ one active vehicle at a time. {@code carIcon}
 * is the color key (e.g. "BLUE"/"RED") the driver picked at registration
 * for how their car marker renders on a rider's map — purely cosmetic, not
 * a domain concept DESIGN.md itself defines.
 */
public record Vehicle(String vehicleId, String driverId, String plate, String make, String model, String productType, CarIcon carIcon) {}

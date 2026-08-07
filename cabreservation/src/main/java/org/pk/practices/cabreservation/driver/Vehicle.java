package org.pk.practices.cabreservation.driver;

/** DESIGN.md §3 — one driver ↔ one active vehicle at a time. */
public record Vehicle(String vehicleId, String driverId, String plate, String make, String model, String productType) {}

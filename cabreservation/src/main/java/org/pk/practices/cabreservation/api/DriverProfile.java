package org.pk.practices.cabreservation.api;

/** A driver's own view of themselves — enough to pre-fill "update my car icon" without exposing anything a rider-facing PartyInfo wouldn't. */
public record DriverProfile(String driverId, String name, VehicleInfo vehicle) {}

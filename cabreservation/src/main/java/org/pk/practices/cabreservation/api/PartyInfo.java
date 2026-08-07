package org.pk.practices.cabreservation.api;

/**
 * What the *other* party on a trip is allowed to see about someone — a name
 * and a rating, not their email/credentials. {@code vehicle} is only ever
 * populated for a driver's {@code PartyInfo} — riders have no vehicle.
 */
public record PartyInfo(String id, String name, Double rating, VehicleInfo vehicle) {}

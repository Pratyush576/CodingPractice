package org.pk.practices.cabreservation.api;

/**
 * What the *other* party on a trip is allowed to see about someone — a name
 * and a rating, not their email/credentials. {@code vehicle}/{@code lat}/
 * {@code lng} are only ever populated for a driver's {@code PartyInfo} —
 * riders have no vehicle and no tracked live position (the trip's own
 * pickup coordinates already say where they are). {@code lat}/{@code lng}
 * come from the driver's last location ping, so they're only fresh insofar
 * as the driver's client is actually pinging.
 */
public record PartyInfo(String id, String name, Double rating, VehicleInfo vehicle, Double lat, Double lng) {}

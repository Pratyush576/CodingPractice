package org.pk.practices.supplychain.booking;

/** Wire shape for {@code POST /v1/bookings/{id}/reserve}. */
public record ReserveCapacityRequest(String offeringId) {}

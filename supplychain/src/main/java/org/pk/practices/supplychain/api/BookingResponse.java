package org.pk.practices.supplychain.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.pk.practices.supplychain.booking.Booking;

/**
 * API-layer view, not a domain object — flattens {@link Booking}'s own
 * fields (via {@code @JsonUnwrapped}) and adds {@code shipperName}, resolved
 * from {@code Party} at response time so an Operator sees who actually
 * created a booking instead of a bare {@code shipperId} UUID. Booking itself
 * stays free of any denormalized Party data.
 */
public record BookingResponse(@JsonUnwrapped Booking booking, String shipperName) {}

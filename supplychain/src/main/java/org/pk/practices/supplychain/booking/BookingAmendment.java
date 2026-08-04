package org.pk.practices.supplychain.booking;

import java.util.List;

/** Wire shape for {@code PUT /v1/bookings/{id}/amend}. expectedVersion drives the CAS check. */
public record BookingAmendment(
        long expectedVersion,
        String requiredPickupBy,
        String requiredDeliveryBy,
        String notifyPartyId,
        List<CargoLineItemRequest> cargoLineItems
) {}

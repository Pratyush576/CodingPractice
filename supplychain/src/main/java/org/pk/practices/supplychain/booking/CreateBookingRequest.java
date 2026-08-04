package org.pk.practices.supplychain.booking;

import java.math.BigDecimal;
import java.util.List;

/**
 * Wire shape for {@code POST /v1/bookings}. Dates are raw strings (ISO-8601)
 * so a bad date becomes a collected {@link ValidationException} violation
 * rather than a Jackson deserialization crash. LLD.md §2 createDraft().
 *
 * <p>No {@code shipperId} field — a Shipper can only ever create a booking
 * for themselves, so it's derived from the authenticated actor, never taken
 * from the request body.
 */
public record CreateBookingRequest(
        String modePreference,
        String incoterm,
        String loadType,
        String originNodeId,
        String destinationNodeId,
        String consigneeId,
        String notifyPartyId,
        String contractId,
        String requiredPickupBy,
        String requiredDeliveryBy,
        BigDecimal totalWeightKg,
        BigDecimal totalVolumeCbm,
        List<ContainerRequirementRequest> containerRequirements,
        List<CargoLineItemRequest> cargoLineItems
) {}

package org.pk.practices.supplychain.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Immutable — transitions are made by building a new instance (e.g.
 * {@link #withStatus}), never by mutating one in place. Matches the
 * "pure function, then compare-and-swap" shape used everywhere else in this
 * system (LLD.md §4 MatchingEngine.reserve()).
 */
public record Booking(
        String tenantId,
        String bookingId,
        BookingStatus status,
        TransportMode modePreference,
        Incoterm incoterm,
        LoadType loadType,
        String originNodeId,
        String destinationNodeId,
        String shipperId,
        String consigneeId,
        String notifyPartyId,
        String contractId,
        Instant requiredPickupBy,
        Instant requiredDeliveryBy,
        BigDecimal totalWeightKg,
        BigDecimal totalVolumeCbm,
        List<ContainerRequirement> containerRequirements,
        List<CargoLineItem> cargoLineItems,
        String capacityOfferingId,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Bumps {@code version} by 1 in the returned copy — {@link PostgresBookingRepository#save}
     * always does the same in the same transaction (its {@code version = version + 1}), so
     * the object hand back to a caller after a successful save must match what's now in the
     * DB, or the caller's next {@code amend()} would submit a stale {@code expectedVersion}.
     */
    public Booking withStatus(BookingStatus newStatus, Instant now) {
        return new Booking(tenantId, bookingId, newStatus, modePreference, incoterm, loadType,
                originNodeId, destinationNodeId, shipperId, consigneeId, notifyPartyId, contractId,
                requiredPickupBy, requiredDeliveryBy, totalWeightKg, totalVolumeCbm,
                containerRequirements, cargoLineItems, capacityOfferingId, version + 1, createdAt, now);
    }

    /** See {@link #withStatus} — same version-bump rule applies here. */
    public Booking withAmendment(Instant newRequiredPickupBy, Instant newRequiredDeliveryBy,
                                  String newNotifyPartyId, List<CargoLineItem> newCargoLineItems, Instant now) {
        return new Booking(tenantId, bookingId, status, modePreference, incoterm, loadType,
                originNodeId, destinationNodeId, shipperId, consigneeId, newNotifyPartyId, contractId,
                newRequiredPickupBy, newRequiredDeliveryBy, totalWeightKg, totalVolumeCbm,
                containerRequirements, newCargoLineItems, capacityOfferingId, version + 1, createdAt, now);
    }

    /** Matching's reserve() succeeded against {@code offeringId} — this is what CONFIRMED means here, absent a real Planning Engine. */
    public Booking withConfirmedCapacity(String offeringId, Instant now) {
        return new Booking(tenantId, bookingId, BookingStatus.CONFIRMED, modePreference, incoterm, loadType,
                originNodeId, destinationNodeId, shipperId, consigneeId, notifyPartyId, contractId,
                requiredPickupBy, requiredDeliveryBy, totalWeightKg, totalVolumeCbm,
                containerRequirements, cargoLineItems, offeringId, version + 1, createdAt, now);
    }
}

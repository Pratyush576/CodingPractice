package org.pk.practices.supplychain.booking;

import org.pk.practices.supplychain.auth.AuthenticatedParty;
import org.pk.practices.supplychain.matching.CapacityOffering;

import java.util.List;
import java.util.Optional;

/**
 * LLD.md §2 Booking Service. Every method takes the authenticated actor, not
 * a bare tenantId — role and ownership are enforced here, not trusted from
 * the caller. See {@link BookingServiceImpl} for the actual rules:
 * only SHIPPER may createDraft(); a SHIPPER may only touch their own
 * bookings; an OPERATOR has full tenant-wide access.
 */
public interface BookingService {
    Booking createDraft(AuthenticatedParty actor, CreateBookingRequest request);
    Booking submit(AuthenticatedParty actor, String bookingId);
    Booking amend(AuthenticatedParty actor, String bookingId, BookingAmendment amendment);
    Booking cancel(AuthenticatedParty actor, String bookingId, String reason);
    Optional<Booking> get(AuthenticatedParty actor, String bookingId);

    /** @param status nullable/blank — every status. Otherwise must name a {@link BookingStatus}. */
    List<Booking> list(AuthenticatedParty actor, String status);

    /**
     * Reserves capacity on {@code offeringId} for this booking and, on success, transitions it
     * to CONFIRMED. Short-circuits Planning Engine (not built) — Matching is called directly.
     */
    Booking reserveCapacity(AuthenticatedParty actor, String bookingId, String offeringId);

    /** Candidate offerings for this booking, per MatchingEngine.findCandidates() — nothing is reserved yet. */
    List<CapacityOffering> findCandidates(AuthenticatedParty actor, String bookingId);
}

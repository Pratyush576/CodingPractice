package org.pk.practices.supplychain.matching;

import org.pk.practices.supplychain.booking.Booking;

import java.util.List;

/** LLD.md §4 Matching Engine — cross-tenant: a Booking from any tenant may match an offering from any other. */
public interface MatchingService {
    List<CapacityOffering> findCandidates(Booking booking);

    ReservationResult reserve(String offeringId, CapacityRequirement requirement);
}

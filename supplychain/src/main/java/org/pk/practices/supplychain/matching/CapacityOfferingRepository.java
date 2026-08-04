package org.pk.practices.supplychain.matching;

import org.pk.practices.supplychain.booking.TransportMode;

import java.util.List;
import java.util.Optional;

/** LLD.md §4 Matching Engine — "Depends on: CapacityOfferingRepository." */
public interface CapacityOfferingRepository {

    void insert(CapacityOffering offering);

    /** Not tenant-scoped — offeringId (UUID) is already globally unique; matching is cross-tenant. */
    Optional<CapacityOffering> find(String offeringId);

    /** operatorId already uniquely identifies which offerings are "this Operator's own" — no tenant scoping needed on top. */
    List<CapacityOffering> findAllForOperator(String operatorId);

    /**
     * The coarse, index-backed filter — mode/lane/status only, across every
     * tenant. Fine-grained "does it actually have enough capacity" is
     * {@link CapacityOffering#hasCapacityFor}, applied in-memory by the
     * caller, same two-step shape as {@code findCandidates()} in LLD.md §4.
     *
     * @param mode nullable — null matches every mode (a booking with modePreference ANY)
     */
    List<CapacityOffering> findCandidates(TransportMode mode, String originNodeId, String destinationNodeId);

    /** Version-checked CAS, identical shape to BookingRepository.save(). @return false if the CAS lost the race. */
    boolean save(CapacityOffering previous, CapacityOffering updated);
}

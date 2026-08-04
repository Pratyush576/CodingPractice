package org.pk.practices.supplychain.matching;

import org.pk.practices.supplychain.booking.Booking;
import org.pk.practices.supplychain.booking.TransportMode;

import java.util.ArrayList;
import java.util.List;

/** LLD.md §4 Matching Engine. */
public class MatchingServiceImpl implements MatchingService {

    private static final int MAX_RETRIES = 5;

    private final CapacityOfferingRepository repository;

    public MatchingServiceImpl(CapacityOfferingRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<CapacityOffering> findCandidates(Booking booking) {
        TransportMode modeFilter = booking.modePreference() == TransportMode.ANY ? null : booking.modePreference();
        // Not scoped to booking.tenantId() — searches every tenant's supply (LLD.md §4).
        List<CapacityOffering> candidates = repository.findCandidates(
                modeFilter, booking.originNodeId(), booking.destinationNodeId());

        CapacityRequirement requirement = CapacityRequirement.from(booking);
        List<CapacityOffering> matching = new ArrayList<>();
        for (CapacityOffering candidate : candidates) {
            if (candidate.hasCapacityFor(requirement)) {
                matching.add(candidate);
            }
        }
        return matching;
    }

    @Override
    public ReservationResult reserve(String offeringId, CapacityRequirement requirement) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            CapacityOffering current = repository.find(offeringId).orElse(null);
            if (current == null || !current.hasCapacityFor(requirement)) {
                return new ReservationResult.Insufficient();
            }
            CapacityOffering updated = current.decrement(requirement); // pure — no side effect yet
            if (repository.save(current, updated)) {
                return new ReservationResult.Success(updated);
            }
            // lost the race — someone else's write landed first; retry with a fresh read
        }
        return new ReservationResult.Contention();
    }
}

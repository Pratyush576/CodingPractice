package org.pk.practices.cabreservation.trip;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TripRepository {
    void insert(Trip trip);
    Optional<Trip> find(String tripId);

    /** Generic version-guarded status transition — {@code UPDATE ... WHERE trip_id=? AND version=?}. */
    boolean compareAndSetStatus(Trip previous, TripStatus newStatus);

    /** MATCHING → MATCHING, but records which driver was offered the trip and until when (§4.3). */
    boolean recordOffer(Trip previous, String driverId, Instant expiresAt);

    /** MATCHING → MATCHED, and sets driverId/matchedAt in the same write. */
    boolean recordMatched(Trip previous, String driverId);

    /** ARRIVED → IN_PROGRESS, and sets startedAt in the same write — the real input to the final fare's duration. */
    boolean recordStarted(Trip previous);

    /** IN_PROGRESS → COMPLETED, and sets completedAt + the computed fareFinal in the same write. */
    boolean recordCompleted(Trip previous, double fareFinal);

    /** Backs MatchOfferTimeoutSweeper — trips still MATCHING whose offer has expired. */
    List<Trip> findExpiredOffers(Instant now);

    /**
     * The most recent non-terminal trip this driver is currently offered or
     * assigned to — lets the Driver App discover a pending offer by polling
     * rather than already knowing the tripId (no push/WebSocket until Phase 3).
     */
    Optional<Trip> findActiveForDriver(String driverId);

    /** A rider's full trip history, most recent first — backs "past rides." */
    List<Trip> findByRiderId(String riderId);

    /** A driver's full trip history, most recent first — backs "past trips." */
    List<Trip> findByDriverId(String driverId);
}

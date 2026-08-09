package org.pk.practices.cabreservation.payment;

import java.util.List;
import java.util.Optional;

public interface PayoutRepository {
    /** Idempotent — returns false (not an error) if a payout for this trip already exists; payouts.trip_id UNIQUE is the real guarantee. */
    boolean insert(Payout payout);

    Optional<Payout> findByTripId(String tripId);

    /** A driver's full earnings history, most recent first — the frontend groups these into time windows (today/week/month/all). */
    List<Payout> findByDriverId(String driverId);
}

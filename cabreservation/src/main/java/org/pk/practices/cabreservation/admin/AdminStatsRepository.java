package org.pk.practices.cabreservation.admin;

import java.time.Instant;

public interface AdminStatsRepository {
    /** {@code since} filters on trips.created_at / payments|payouts.created_at — pass Instant.EPOCH for "all time" rather than threading a nullable bound through every query. */
    AdminStats compute(Instant since);
}

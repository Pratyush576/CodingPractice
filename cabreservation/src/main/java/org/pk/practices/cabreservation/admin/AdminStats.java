package org.pk.practices.cabreservation.admin;

/**
 * Platform-wide counts/sums for a given time window — the golden-signal
 * rollup DESIGN.md §7 calls for, computed on demand rather than tracked
 * incrementally. {@code matched} counts trips that ever reached MATCHED
 * (i.e. {@code matched_at IS NOT NULL}), regardless of what happened after
 * (including a later cancellation) — "was a driver ever assigned," not
 * "is currently matched."
 *
 * <p>{@code totalRevenue}/{@code totalPayouts} are what actually settled
 * (CHARGED/PAID only); {@code totalFareValue} is the gross fare across every
 * COMPLETED trip regardless of whether the charge succeeded — the gap
 * between the two, together with {@code declinedPaymentsAmount}, is what
 * exposes silent collection failures rather than hiding them inside a
 * single "revenue" number.
 */
public record AdminStats(
        long requested,
        long matched,
        long completed,
        long cancelledByRider,
        long cancelledByDriver,
        long noDriversFound,
        double totalRevenue,
        double totalPayouts,
        long paymentsCount,
        long payoutsCount,
        double totalFareValue,
        double declinedPaymentsAmount,
        long declinedPaymentsCount,
        double failedPayoutsAmount,
        long failedPayoutsCount
) {}

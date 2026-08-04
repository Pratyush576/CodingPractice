package org.pk.practices.supplychain.matching;

/** LLD.md §4 Matching Engine's reserve() outcomes — a sealed type rather than throwing, since Insufficient/Contention are both routine, expected results, not error conditions. */
public sealed interface ReservationResult {
    record Success(CapacityOffering updated) implements ReservationResult {}
    record Insufficient() implements ReservationResult {}
    record Contention() implements ReservationResult {}
}

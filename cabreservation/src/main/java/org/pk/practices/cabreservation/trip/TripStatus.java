package org.pk.practices.cabreservation.trip;

/** DESIGN.md §4.4's state machine — exactly these states, no others. */
public enum TripStatus {
    REQUESTED,
    MATCHING,
    MATCHED,
    NO_DRIVERS_FOUND,
    DRIVER_ARRIVING,
    ARRIVED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED_BY_RIDER,
    CANCELLED_BY_DRIVER
}

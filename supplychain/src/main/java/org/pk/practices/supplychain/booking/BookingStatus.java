package org.pk.practices.supplychain.booking;

/** DESIGN.md §3 Booking lifecycle. CONFIRMED is set by the Planning Engine (not yet built). */
public enum BookingStatus {
    DRAFT,
    SUBMITTED,
    CONFIRMED,
    CANCELLED
}

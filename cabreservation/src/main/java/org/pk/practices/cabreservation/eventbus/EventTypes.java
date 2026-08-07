package org.pk.practices.cabreservation.eventbus;

/** String constants for the event types this module publishes/subscribes to (DESIGN.md §2). */
public final class EventTypes {
    public static final String TRIP_REQUESTED = "TRIP_REQUESTED";
    public static final String TRIP_OFFERED = "TRIP_OFFERED";
    public static final String TRIP_MATCHED = "TRIP_MATCHED";
    public static final String NO_DRIVERS_FOUND = "NO_DRIVERS_FOUND";
    public static final String TRIP_COMPLETED = "TRIP_COMPLETED";
    public static final String INVOICE_ISSUED = "INVOICE_ISSUED";
    public static final String PAYOUT_COMPLETED = "PAYOUT_COMPLETED";

    private EventTypes() {}
}

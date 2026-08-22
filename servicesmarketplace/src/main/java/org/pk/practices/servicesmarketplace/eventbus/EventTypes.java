package org.pk.practices.servicesmarketplace.eventbus;

/** String constants for the event types this module publishes/subscribes to (DESIGN.md §2/§4). */
public final class EventTypes {
    public static final String REQUEST_POSTED = "REQUEST_POSTED";
    public static final String LEAD_CREATED = "LEAD_CREATED";
    public static final String QUOTE_SENT = "QUOTE_SENT";
    public static final String REQUEST_HIRED = "REQUEST_HIRED";
    public static final String JOB_COMPLETED = "JOB_COMPLETED";

    private EventTypes() {}
}

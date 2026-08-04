package org.pk.practices.supplychain.booking;

/** DESIGN.md §4.3 Multi-Modal Transport Abstraction. ANY defers mode selection to Matching. */
public enum TransportMode {
    OCEAN,
    AIR,
    ROAD_FTL,
    ROAD_LTL,
    RAIL,
    PARCEL,
    INTERMODAL,
    ANY
}

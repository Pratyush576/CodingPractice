package org.pk.practices.supplychain.party;

/**
 * DESIGN.md §3 models a much wider {@code Party} (Shipper, Consignee, Operator,
 * Carrier, Freight Forwarder, Customs Broker, ...). This UI only needs to
 * distinguish the two roles that actually log into it — extend when another
 * component needs its own Party-backed actor type.
 */
public enum PartyRole {
    SHIPPER,
    OPERATOR
}

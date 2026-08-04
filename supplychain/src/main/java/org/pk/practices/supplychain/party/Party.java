package org.pk.practices.supplychain.party;

import java.time.Instant;

/** DESIGN.md §3 Party — narrowed to what a logged-in Shipper/Operator account needs. */
public record Party(
        String partyId,
        String tenantId,
        PartyRole role,
        String name,
        String email,
        String passwordHash,
        Instant createdAt
) {}

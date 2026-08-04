package org.pk.practices.supplychain.auth;

import org.pk.practices.supplychain.party.PartyRole;

/** What a resolved session token actually authorizes — LLD.md §15's "AuthenticatedPrincipal." */
public record AuthenticatedParty(String partyId, String tenantId, PartyRole role, String name) {}

package org.pk.practices.supplychain.auth;

import org.pk.practices.supplychain.party.PartyRole;

/** Returned by both register() and login() — the UI stores {@code token} and uses the rest to render itself. */
public record LoginResult(String token, String partyId, String tenantId, PartyRole role, String name) {}

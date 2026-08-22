package org.pk.practices.servicesmarketplace.auth;

/** What a resolved Bearer token yields — every protected route reads the caller's id/type from here, never from the request body. */
public record AuthenticatedAccount(String accountId, AccountType accountType, String name) {}

package org.pk.practices.servicesmarketplace.auth;

public record LoginResult(String token, String accountId, AccountType accountType, String name) {}

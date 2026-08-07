package org.pk.practices.cabreservation.auth;

public record LoginResult(String token, String accountId, AccountType accountType, String name) {}

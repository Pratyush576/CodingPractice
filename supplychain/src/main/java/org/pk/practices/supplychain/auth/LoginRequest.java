package org.pk.practices.supplychain.auth;

/** Wire shape for {@code POST /v1/auth/login}. */
public record LoginRequest(String email, String password) {}

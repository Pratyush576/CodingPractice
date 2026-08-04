package org.pk.practices.supplychain.common;

/** Identity is known, but this actor's role/ownership doesn't permit the action — 403. LLD.md §15. */
public class AuthorizationException extends RuntimeException {
    public AuthorizationException(String message) {
        super(message);
    }
}

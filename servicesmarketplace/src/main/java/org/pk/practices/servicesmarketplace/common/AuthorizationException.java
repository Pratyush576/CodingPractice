package org.pk.practices.servicesmarketplace.common;

/** Identity is known, but this actor's role doesn't permit the action — 403 (e.g. a customer token calling a Pro-only endpoint). */
public class AuthorizationException extends RuntimeException {
    public AuthorizationException(String message) {
        super(message);
    }
}

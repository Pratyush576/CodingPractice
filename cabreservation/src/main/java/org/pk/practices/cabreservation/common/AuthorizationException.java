package org.pk.practices.cabreservation.common;

/** Identity is known, but this actor's role doesn't permit the action — 403 (e.g. a rider token calling a driver-only endpoint). */
public class AuthorizationException extends RuntimeException {
    public AuthorizationException(String message) {
        super(message);
    }
}

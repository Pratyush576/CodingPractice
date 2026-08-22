package org.pk.practices.servicesmarketplace.common;

/** Credential missing or invalid — 401. Distinct from {@link AuthorizationException}: this means "who are you," not "you can't do that." */
public class AuthenticationException extends RuntimeException {
    public AuthenticationException(String message) {
        super(message);
    }
}

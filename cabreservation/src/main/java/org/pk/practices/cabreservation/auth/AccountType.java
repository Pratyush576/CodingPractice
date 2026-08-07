package org.pk.practices.cabreservation.auth;

/** Which table an authenticated identity resolved against — Rider and Driver are distinct entities, not one polymorphic Party. */
public enum AccountType {
    RIDER,
    DRIVER
}

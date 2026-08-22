package org.pk.practices.servicesmarketplace.credit;

/** DESIGN.md §4.3 — {@code ALREADY_UNLOCKED} is the idempotent no-op path, not an error. */
public enum UnlockResult {
    UNLOCKED,
    ALREADY_UNLOCKED
}

package org.pk.practices.supplychain.auth;

/**
 * Registering isn't always a straight success/failure — a brand-new tenantId
 * (the common case is a typo, not an actual new company) needs a
 * confirmation round-trip first. Modeled as a sealed type rather than an
 * exception because it isn't an error: it's a legitimate second outcome the
 * caller is expected to handle.
 */
public sealed interface RegisterOutcome {
    record Registered(LoginResult result) implements RegisterOutcome {}
    record NewTenantConfirmationRequired(String tenantId) implements RegisterOutcome {}
}

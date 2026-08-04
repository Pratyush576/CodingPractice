package org.pk.practices.supplychain.auth;

/**
 * Wire shape for {@code POST /v1/auth/register}. {@code confirmNewTenant}
 * defaults to false (absent/null) on a first submission — the client
 * resubmits with it set to true after the user confirms they really do want
 * to create a brand-new tenant, not join an existing one under a typo'd ID.
 */
public record RegisterRequest(String tenantId, String role, String name, String email, String password, Boolean confirmNewTenant) {
    public boolean isConfirmedNewTenant() {
        return Boolean.TRUE.equals(confirmNewTenant);
    }
}

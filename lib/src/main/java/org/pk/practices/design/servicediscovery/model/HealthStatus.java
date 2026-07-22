package org.pk.practices.design.servicediscovery.model;

/**
 * Lifecycle states of a registered service instance from the perspective of the
 * health check engine.
 *
 * <pre>
 *   UNKNOWN ──[first check passes]──► HEALTHY
 *   UNKNOWN ──[first check fails] ──► UNHEALTHY
 *   HEALTHY ──[N consecutive fails]──► UNHEALTHY
 *   UNHEALTHY ──[check passes]    ──► HEALTHY
 * </pre>
 *
 * {@code UNKNOWN} is the initial state immediately after registration, before the
 * first health check result is recorded. Whether unknown instances are included in
 * lookup results is a policy decision (configurable via {@code LookupQuery.healthyOnly}).
 */
public enum HealthStatus {

    /** Registration received; no health check result yet. */
    UNKNOWN,

    /** All recent health checks passed. Instance is eligible for traffic. */
    HEALTHY,

    /** Health check(s) failed or heartbeat TTL expired. Excluded from lookups by default. */
    UNHEALTHY
}

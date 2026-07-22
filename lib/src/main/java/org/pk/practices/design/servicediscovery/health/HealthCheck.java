package org.pk.practices.design.servicediscovery.health;

import org.pk.practices.design.servicediscovery.model.ServiceInstance;

/**
 * Strategy interface for checking whether a service instance is alive.
 *
 * <h2>Extension points</h2>
 * <ul>
 *   <li>{@link TcpHealthCheck} — attempts a TCP socket connection</li>
 *   <li>{@link HeartbeatHealthCheck} — checks last-heartbeat timestamp against a TTL</li>
 *   <li>HTTP check — GET /health and assert 2xx (not included; needs an HTTP client dep)</li>
 *   <li>gRPC check — Health Checking Protocol (GRPC_STATUS_SERVING)</li>
 *   <li>Custom script — shell out and check exit code</li>
 * </ul>
 *
 * <h2>Contract</h2>
 * Implementations must be thread-safe: the scheduler may call {@code check()} from
 * multiple threads concurrently for different instances.
 * Implementations should not throw — return {@code false} on any error.
 */
@FunctionalInterface
public interface HealthCheck {
    /**
     * @param instance the instance to check
     * @return {@code true} if the instance is considered healthy
     */
    boolean check(ServiceInstance instance);
}

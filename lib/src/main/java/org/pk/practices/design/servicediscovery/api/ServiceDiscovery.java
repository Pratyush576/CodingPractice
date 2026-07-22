package org.pk.practices.design.servicediscovery.api;

import org.pk.practices.design.servicediscovery.model.DnsRecord;
import org.pk.practices.design.servicediscovery.model.InstanceState;
import org.pk.practices.design.servicediscovery.model.ServiceInstance;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Top-level API for the service discovery platform.
 *
 * <h2>Layer responsibilities</h2>
 * <pre>
 *   ServiceDiscovery (this interface)
 *     ├── ServiceRegistry    — storage: register / deregister / query
 *     ├── HealthCheckScheduler — background health probing
 *     ├── DnsResolver        — synthesise DNS records from registry state
 *     └── LoadBalancingStrategy — pick one instance from healthy set
 * </pre>
 *
 * <h2>Concurrency model</h2>
 * All methods on this interface must be safe to call from multiple threads concurrently.
 * The underlying registry uses ConcurrentHashMap; the health scheduler runs on a
 * daemon thread pool.
 */
public interface ServiceDiscovery {

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Starts the health check scheduler. Must be called before using the platform. */
    void start();

    /** Stops the health check scheduler and releases resources. */
    void shutdown();

    // ── Registration ─────────────────────────────────────────────────────────

    /** Registers a new instance. Re-registering the same instanceId overwrites. */
    void register(ServiceInstance instance);

    /** Permanently removes an instance from the registry. */
    void deregister(String namespace, String serviceName, String instanceId);

    /**
     * Records a heartbeat from an instance, resetting its TTL.
     * Must be called at intervals shorter than the configured heartbeat TTL.
     */
    void heartbeat(String namespace, String serviceName, String instanceId);

    // ── Lookup ────────────────────────────────────────────────────────────────

    /**
     * Returns all healthy instances of a service.
     * Use {@link #pick} to select a single instance for a request.
     */
    List<ServiceInstance> lookup(String namespace, String serviceName);

    /**
     * Returns all instances including unhealthy ones.
     * Useful for debugging and monitoring dashboards.
     */
    List<InstanceState> inspect(String namespace, String serviceName);

    // ── Load-balanced pick ────────────────────────────────────────────────────

    /**
     * Picks one healthy instance using the configured load balancing strategy.
     *
     * @return empty if no healthy instance is available
     */
    Optional<ServiceInstance> pick(String namespace, String serviceName);

    // ── DNS ───────────────────────────────────────────────────────────────────

    /** Resolves A records (IP addresses) for all healthy instances of a service. */
    List<DnsRecord> resolveA(String namespace, String serviceName);

    /** Resolves SRV records (host + port) for all healthy instances of a service. */
    List<DnsRecord> resolveSrv(String namespace, String serviceName);

    // ── Monitoring ────────────────────────────────────────────────────────────

    /** Summary statistics across all namespaces. */
    Map<String, Object> stats();
}

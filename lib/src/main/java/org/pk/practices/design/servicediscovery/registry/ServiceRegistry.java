package org.pk.practices.design.servicediscovery.registry;

import org.pk.practices.design.servicediscovery.model.HealthStatus;
import org.pk.practices.design.servicediscovery.model.InstanceState;
import org.pk.practices.design.servicediscovery.model.ServiceInstance;

import java.util.List;
import java.util.Optional;

/**
 * Core storage interface for the service registry.
 *
 * <h2>Responsibility</h2>
 * The registry is a pure storage component — it stores and retrieves instance data.
 * It knows nothing about health check scheduling, DNS, or load balancing. Those
 * concerns live in higher layers.
 *
 * <h2>In production</h2>
 * This interface would be backed by a distributed, replicated key-value store
 * (etcd, Consul KV, or ZooKeeper). The in-memory implementation here is suitable
 * for a single-node demo and unit testing.
 *
 * <h2>Key structure</h2>
 * {@code namespace / serviceName / instanceId → InstanceState}
 * Partitioning by namespace enables tenant isolation and independent scaling.
 */
public interface ServiceRegistry {

    /** Registers a new instance or overwrites an existing one with the same ID. */
    void register(ServiceInstance instance);

    /**
     * Removes the instance from the registry entirely.
     * Callers must have first confirmed the instance should be permanently gone,
     * not just temporarily unhealthy.
     */
    void deregister(String namespace, String serviceName, String instanceId);

    /** Returns the runtime state of a specific instance, if it exists. */
    Optional<InstanceState> getInstance(String namespace, String serviceName, String instanceId);

    /** Returns all instances (healthy and unhealthy) registered for a service. */
    List<InstanceState> getInstances(String namespace, String serviceName);

    /** Returns all instances across all services in the given namespace. */
    List<InstanceState> getAllInNamespace(String namespace);

    /** Returns every registered instance across all namespaces. */
    List<InstanceState> getAll();

    /** Updates the health status of a specific instance. Called by the health check engine. */
    void updateHealth(String namespace, String serviceName, String instanceId, HealthStatus status);

    /** Records a heartbeat ping from an instance, resetting its TTL clock. */
    void recordHeartbeat(String namespace, String serviceName, String instanceId);

    /** Total number of registered instances across all namespaces. */
    int totalCount();
}

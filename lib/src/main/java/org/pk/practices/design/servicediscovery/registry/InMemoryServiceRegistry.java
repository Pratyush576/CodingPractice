package org.pk.practices.design.servicediscovery.registry;

import org.pk.practices.design.servicediscovery.model.HealthStatus;
import org.pk.practices.design.servicediscovery.model.InstanceState;
import org.pk.practices.design.servicediscovery.model.ServiceInstance;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Thread-safe, in-memory implementation of {@link ServiceRegistry}.
 *
 * <h2>Data structure</h2>
 * <pre>
 *   ConcurrentHashMap                        ← namespace index
 *     └── namespace ("production")
 *         └── ConcurrentHashMap              ← service index
 *               └── serviceName ("user-service")
 *                   └── ConcurrentHashMap    ← instance index
 *                         └── instanceId → InstanceState
 * </pre>
 * This three-level structure gives O(1) access for all lookup patterns:
 * <ul>
 *   <li>Single instance: three direct gets</li>
 *   <li>All instances of a service: two gets + one values() call</li>
 *   <li>All instances in a namespace: one get + flatMap over services</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * {@link ConcurrentHashMap} provides fine-grained segment locking so reads and writes
 * on different keys proceed concurrently. {@code computeIfAbsent} ensures the inner
 * maps are created exactly once even under concurrent registration.
 *
 * <h2>In production</h2>
 * This would be replaced by a distributed KV store (etcd / Consul) with:
 * <ul>
 *   <li>Raft-based replication for durability</li>
 *   <li>TTL-based auto-expiry for crash detection</li>
 *   <li>Watch / subscribe for change notifications</li>
 * </ul>
 */
public final class InMemoryServiceRegistry implements ServiceRegistry {

    // namespace → serviceName → instanceId → InstanceState
    private final ConcurrentHashMap<
            String,
            ConcurrentHashMap<
                    String,
                    ConcurrentHashMap<String, InstanceState>>> store = new ConcurrentHashMap<>();

    // ── Write operations ──────────────────────────────────────────────────────

    @Override
    public void register(ServiceInstance instance) {
        serviceMap(instance.namespace(), instance.serviceName())
                .put(instance.instanceId(), new InstanceState(instance));
    }

    @Override
    public void deregister(String namespace, String serviceName, String instanceId) {
        ConcurrentHashMap<String, InstanceState> instances = serviceMapOrNull(namespace, serviceName);
        if (instances != null) {
            instances.remove(instanceId);
            // Prune empty service maps to avoid memory leak over time
            if (instances.isEmpty()) {
                ConcurrentHashMap<String, ConcurrentHashMap<String, InstanceState>> services =
                        store.get(namespace);
                if (services != null) services.remove(serviceName);
            }
        }
    }

    @Override
    public void updateHealth(String namespace, String serviceName, String instanceId,
                             HealthStatus status) {
        InstanceState state = getInstanceState(namespace, serviceName, instanceId);
        if (state == null) return;
        if (status == HealthStatus.HEALTHY) {
            state.markHealthy();
        } else {
            state.markUnhealthy();
        }
    }

    @Override
    public void recordHeartbeat(String namespace, String serviceName, String instanceId) {
        InstanceState state = getInstanceState(namespace, serviceName, instanceId);
        if (state != null) state.recordHeartbeat();
    }

    // ── Read operations ───────────────────────────────────────────────────────

    @Override
    public Optional<InstanceState> getInstance(String namespace, String serviceName,
                                               String instanceId) {
        return Optional.ofNullable(getInstanceState(namespace, serviceName, instanceId));
    }

    @Override
    public List<InstanceState> getInstances(String namespace, String serviceName) {
        ConcurrentHashMap<String, InstanceState> instances = serviceMapOrNull(namespace, serviceName);
        return instances == null
                ? Collections.emptyList()
                : List.copyOf(instances.values());
    }

    @Override
    public List<InstanceState> getAllInNamespace(String namespace) {
        ConcurrentHashMap<String, ConcurrentHashMap<String, InstanceState>> services =
                store.get(namespace);
        if (services == null) return Collections.emptyList();
        return services.values().stream()
                .flatMap(m -> m.values().stream())
                .toList();
    }

    @Override
    public List<InstanceState> getAll() {
        return store.values().stream()
                .flatMap(services -> services.values().stream())
                .flatMap(instances -> instances.values().stream())
                .toList();
    }

    @Override
    public int totalCount() {
        return store.values().stream()
                .flatMap(s -> s.values().stream())
                .mapToInt(Map::size)
                .sum();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private ConcurrentHashMap<String, InstanceState> serviceMap(String namespace, String service) {
        return store
                .computeIfAbsent(namespace, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(service,   k -> new ConcurrentHashMap<>());
    }

    private ConcurrentHashMap<String, InstanceState> serviceMapOrNull(String namespace,
                                                                        String service) {
        ConcurrentHashMap<String, ConcurrentHashMap<String, InstanceState>> ns = store.get(namespace);
        return ns == null ? null : ns.get(service);
    }

    private InstanceState getInstanceState(String namespace, String service, String instanceId) {
        ConcurrentHashMap<String, InstanceState> instances = serviceMapOrNull(namespace, service);
        return instances == null ? null : instances.get(instanceId);
    }

    /** Returns all known namespace names (useful for monitoring). */
    public Stream<String> namespaces() { return store.keySet().stream(); }

    /** Returns all known service names within a namespace. */
    public Stream<String> servicesInNamespace(String namespace) {
        ConcurrentHashMap<String, ConcurrentHashMap<String, InstanceState>> ns = store.get(namespace);
        return ns == null ? Stream.empty() : ns.keySet().stream();
    }
}

package org.pk.practices.design.servicediscovery.api;

import org.pk.practices.design.servicediscovery.dns.DnsResolver;
import org.pk.practices.design.servicediscovery.health.HealthCheck;
import org.pk.practices.design.servicediscovery.health.HealthCheckScheduler;
import org.pk.practices.design.servicediscovery.lb.LoadBalancingStrategy;
import org.pk.practices.design.servicediscovery.lb.RoundRobinStrategy;
import org.pk.practices.design.servicediscovery.model.DnsRecord;
import org.pk.practices.design.servicediscovery.model.HealthStatus;
import org.pk.practices.design.servicediscovery.model.InstanceState;
import org.pk.practices.design.servicediscovery.model.ServiceInstance;
import org.pk.practices.design.servicediscovery.registry.InMemoryServiceRegistry;
import org.pk.practices.design.servicediscovery.registry.ServiceRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default wiring of all service discovery components.
 *
 * <h2>Component graph</h2>
 * <pre>
 *   DefaultServiceDiscovery
 *     ├── ServiceRegistry         (InMemoryServiceRegistry)
 *     ├── HealthCheckScheduler    (wraps HealthCheck strategy)
 *     ├── DnsResolver             (reads registry, synthesises records)
 *     └── LoadBalancingStrategy   (RoundRobin by default, injectable)
 * </pre>
 *
 * Use the {@link Builder} for custom configuration.
 */
public final class DefaultServiceDiscovery implements ServiceDiscovery {

    private final ServiceRegistry        registry;
    private final HealthCheckScheduler   scheduler;
    private final DnsResolver            dnsResolver;
    private final LoadBalancingStrategy  lbStrategy;
    private final long                   checkIntervalMs;

    private DefaultServiceDiscovery(Builder b) {
        this.registry        = b.registry;
        this.lbStrategy      = b.lbStrategy;
        this.checkIntervalMs = b.checkIntervalMs;
        this.dnsResolver     = new DnsResolver(registry, b.dnsTtlSeconds);
        this.scheduler       = new HealthCheckScheduler(registry, b.healthCheck,
                b.unhealthyThreshold, b.schedulerThreads);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void start() {
        scheduler.start(checkIntervalMs);
    }

    @Override
    public void shutdown() {
        scheduler.shutdown();
    }

    // ── Registration ─────────────────────────────────────────────────────────

    @Override
    public void register(ServiceInstance instance) {
        registry.register(instance);
    }

    @Override
    public void deregister(String namespace, String serviceName, String instanceId) {
        registry.deregister(namespace, serviceName, instanceId);
    }

    @Override
    public void heartbeat(String namespace, String serviceName, String instanceId) {
        registry.recordHeartbeat(namespace, serviceName, instanceId);
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    @Override
    public List<ServiceInstance> lookup(String namespace, String serviceName) {
        return registry.getInstances(namespace, serviceName).stream()
                .filter(s -> s.healthStatus() == HealthStatus.HEALTHY)
                .map(InstanceState::instance)
                .toList();
    }

    @Override
    public List<InstanceState> inspect(String namespace, String serviceName) {
        return registry.getInstances(namespace, serviceName);
    }

    // ── Load-balanced pick ────────────────────────────────────────────────────

    @Override
    public Optional<ServiceInstance> pick(String namespace, String serviceName) {
        List<ServiceInstance> healthy = lookup(namespace, serviceName);
        return lbStrategy.pick(healthy);
    }

    // ── DNS ───────────────────────────────────────────────────────────────────

    @Override
    public List<DnsRecord> resolveA(String namespace, String serviceName) {
        return dnsResolver.resolveA(namespace, serviceName);
    }

    @Override
    public List<DnsRecord> resolveSrv(String namespace, String serviceName) {
        return dnsResolver.resolveSrv(namespace, serviceName);
    }

    // ── Monitoring ────────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> stats() {
        List<InstanceState> all   = registry.getAll();
        long healthy   = all.stream().filter(InstanceState::isHealthy).count();
        long unhealthy = all.stream().filter(InstanceState::isUnhealthy).count();
        long unknown   = all.stream()
                .filter(s -> s.healthStatus() == HealthStatus.UNKNOWN).count();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalInstances",     all.size());
        m.put("healthyInstances",   healthy);
        m.put("unhealthyInstances", unhealthy);
        m.put("unknownInstances",   unknown);
        m.put("checkIntervalMs",    checkIntervalMs);
        m.put("lbStrategy",         lbStrategy.name());
        return m;
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder(HealthCheck healthCheck) {
        return new Builder(healthCheck);
    }

    public static final class Builder {
        private final HealthCheck       healthCheck;
        private ServiceRegistry         registry           = new InMemoryServiceRegistry();
        private LoadBalancingStrategy   lbStrategy         = new RoundRobinStrategy();
        private long                    checkIntervalMs    = 2_000;
        private int                     unhealthyThreshold = 2;
        private int                     schedulerThreads   = 4;
        private int                     dnsTtlSeconds      = 10;

        private Builder(HealthCheck healthCheck) { this.healthCheck = healthCheck; }

        public Builder registry(ServiceRegistry r)           { this.registry           = r;  return this; }
        public Builder lbStrategy(LoadBalancingStrategy s)   { this.lbStrategy         = s;  return this; }
        public Builder checkIntervalMs(long ms)              { this.checkIntervalMs    = ms; return this; }
        public Builder unhealthyThreshold(int n)             { this.unhealthyThreshold = n;  return this; }
        public Builder schedulerThreads(int n)               { this.schedulerThreads   = n;  return this; }
        public Builder dnsTtlSeconds(int s)                  { this.dnsTtlSeconds      = s;  return this; }

        public DefaultServiceDiscovery build() { return new DefaultServiceDiscovery(this); }
    }
}

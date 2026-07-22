package org.pk.practices.design.servicediscovery.health;

import org.pk.practices.design.servicediscovery.model.InstanceState;
import org.pk.practices.design.servicediscovery.model.ServiceInstance;
import org.pk.practices.design.servicediscovery.registry.ServiceRegistry;

import java.time.Duration;
import java.time.Instant;

/**
 * Health check that declares an instance unhealthy when its heartbeat goes stale.
 *
 * <h2>Model</h2>
 * Each registered instance is expected to call {@code heartbeat()} at regular intervals.
 * This check reads the {@link InstanceState#lastHeartbeat()} timestamp from the registry
 * and compares it against a configured TTL. If {@code now - lastHeartbeat > ttl},
 * the instance has not phoned home and is assumed dead.
 *
 * <h2>Why TTL-based instead of active probing?</h2>
 * <ul>
 *   <li>Sidesteps firewall / VPC rules that may block inbound TCP to sidecar processes</li>
 *   <li>Works well for serverless/ephemeral workloads that cannot host a server socket</li>
 *   <li>Reduces check load: one heartbeat per instance per TTL period vs. continuous polling</li>
 * </ul>
 * Combine with {@link TcpHealthCheck} for defence-in-depth.
 */
public final class HeartbeatHealthCheck implements HealthCheck {

    private final ServiceRegistry registry;
    private final Duration        ttl;

    public HeartbeatHealthCheck(ServiceRegistry registry, Duration ttl) {
        this.registry = registry;
        this.ttl      = ttl;
    }

    @Override
    public boolean check(ServiceInstance instance) {
        return registry.getInstance(
                        instance.namespace(),
                        instance.serviceName(),
                        instance.instanceId())
                .map(state -> Duration.between(state.lastHeartbeat(), Instant.now()).compareTo(ttl) <= 0)
                .orElse(false);
    }
}

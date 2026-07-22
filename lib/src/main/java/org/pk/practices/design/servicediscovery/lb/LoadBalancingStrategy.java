package org.pk.practices.design.servicediscovery.lb;

import org.pk.practices.design.servicediscovery.model.ServiceInstance;

import java.util.List;
import java.util.Optional;

/**
 * Strategy for selecting a single instance from a set of healthy candidates.
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link RoundRobinStrategy} — deterministic even distribution, good default</li>
 *   <li>{@link RandomStrategy} — stateless, good for cache/fan-out where stickiness hurts</li>
 *   <li>Least-connections — requires live connection-count telemetry (not included)</li>
 *   <li>Weighted — reads a "weight" key from instance metadata</li>
 *   <li>Consistent-hashing — routes the same caller always to the same instance
 *       (session affinity, request coalescing)</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * All implementations must be safe for concurrent calls from multiple client goroutines.
 */
public interface LoadBalancingStrategy {

    /** Human-readable name shown in logs and metrics. */
    String name();

    /**
     * Picks one instance from the provided list.
     *
     * @param candidates non-null, may be empty
     * @return the selected instance, or empty if the list is empty
     */
    Optional<ServiceInstance> pick(List<ServiceInstance> candidates);
}

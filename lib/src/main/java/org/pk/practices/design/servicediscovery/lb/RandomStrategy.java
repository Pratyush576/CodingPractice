package org.pk.practices.design.servicediscovery.lb;

import org.pk.practices.design.servicediscovery.model.ServiceInstance;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stateless random load balancer.
 *
 * <h2>When to prefer over RoundRobin</h2>
 * <ul>
 *   <li>Fan-out scatter-gather: you want independent random selection per sub-request
 *       so that correlated failures don't hit the same downstream every time</li>
 *   <li>Cache invalidation workloads: spreading misses randomly reduces thundering herd</li>
 *   <li>Lambda / serverless callers that do not share state between invocations</li>
 * </ul>
 *
 * <h2>ThreadLocalRandom</h2>
 * Avoids contention on a shared {@code Random} by using per-thread state.
 * {@link ThreadLocalRandom#current()} is always available without initialisation.
 */
public final class RandomStrategy implements LoadBalancingStrategy {

    @Override
    public String name() { return "Random"; }

    @Override
    public Optional<ServiceInstance> pick(List<ServiceInstance> candidates) {
        if (candidates.isEmpty()) return Optional.empty();
        int idx = ThreadLocalRandom.current().nextInt(candidates.size());
        return Optional.of(candidates.get(idx));
    }
}

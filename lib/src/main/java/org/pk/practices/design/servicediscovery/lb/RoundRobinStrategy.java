package org.pk.practices.design.servicediscovery.lb;

import org.pk.practices.design.servicediscovery.model.ServiceInstance;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free round-robin load balancer using a global AtomicLong counter.
 *
 * <h2>Algorithm</h2>
 * <pre>
 *   index = counter.getAndIncrement() % candidates.size()
 * </pre>
 * The counter is never reset — it wraps around naturally at Long.MAX_VALUE
 * (after ~9 × 10¹⁸ requests, safe to ignore). Modulo on a positive long is always
 * non-negative, so no abs() call is needed.
 *
 * <h2>Caveat: list size changes</h2>
 * If a healthy instance deregisters mid-flight the candidate list shrinks. The next
 * modulo automatically re-maps to the new list without any state reset, so distribution
 * stays approximately even after the change. No locking is required.
 *
 * <h2>Thread safety</h2>
 * {@link AtomicLong#getAndIncrement()} is a single CAS instruction on x86 — extremely
 * low contention even at millions of picks per second across many threads.
 */
public final class RoundRobinStrategy implements LoadBalancingStrategy {

    private final AtomicLong counter = new AtomicLong(0);

    @Override
    public String name() { return "RoundRobin"; }

    @Override
    public Optional<ServiceInstance> pick(List<ServiceInstance> candidates) {
        if (candidates.isEmpty()) return Optional.empty();
        int idx = (int) (counter.getAndIncrement() % candidates.size());
        return Optional.of(candidates.get(idx));
    }
}

package org.pk.practices.design.servicediscovery.model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable runtime state wrapping an immutable {@link ServiceInstance}.
 *
 * <h2>Why separate from ServiceInstance?</h2>
 * {@link ServiceInstance} holds the registration data — it never changes after the
 * instance is registered. {@code InstanceState} holds the health check engine's
 * view of that instance, which changes continuously as checks run.
 * Keeping them separate means the registry can update health without re-serialising
 * the full instance record.
 *
 * <h2>Thread safety</h2>
 * All mutable fields use either {@code volatile} or {@link AtomicReference} /
 * {@link AtomicInteger} so the health check scheduler threads and reader threads
 * can access state concurrently without locks.
 *
 * <h2>Failure threshold</h2>
 * {@link #consecutiveFailures} is incremented on each failed health check and reset
 * to zero on success. The health check scheduler marks an instance UNHEALTHY only
 * after it exceeds a configured failure threshold — preventing a single transient
 * blip from pulling an instance out of rotation.
 */
public final class InstanceState {

    private final ServiceInstance instance;
    private final AtomicReference<HealthStatus> healthStatus;
    private volatile Instant lastHeartbeat;
    private volatile Instant lastChecked;
    private final AtomicInteger consecutiveFailures;

    public InstanceState(ServiceInstance instance) {
        this.instance            = instance;
        this.healthStatus        = new AtomicReference<>(HealthStatus.UNKNOWN);
        this.lastHeartbeat       = Instant.now();
        this.lastChecked         = Instant.EPOCH;
        this.consecutiveFailures = new AtomicInteger(0);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public ServiceInstance instance()          { return instance; }
    public HealthStatus     healthStatus()     { return healthStatus.get(); }
    public Instant          lastHeartbeat()    { return lastHeartbeat; }
    public Instant          lastChecked()      { return lastChecked; }
    public int              consecutiveFailures() { return consecutiveFailures.get(); }

    public boolean isHealthy()   { return healthStatus.get() == HealthStatus.HEALTHY; }
    public boolean isUnhealthy() { return healthStatus.get() == HealthStatus.UNHEALTHY; }

    // ── Mutators (called by health check scheduler) ───────────────────────────

    public void markHealthy() {
        healthStatus.set(HealthStatus.HEALTHY);
        consecutiveFailures.set(0);
        lastChecked = Instant.now();
    }

    public void markUnhealthy() {
        healthStatus.set(HealthStatus.UNHEALTHY);
        lastChecked = Instant.now();
    }

    /** Increments the failure counter and returns the updated count. */
    public int recordFailure() {
        lastChecked = Instant.now();
        return consecutiveFailures.incrementAndGet();
    }

    /** Called when the instance sends a heartbeat ping. Resets the TTL clock. */
    public void recordHeartbeat() {
        lastHeartbeat = Instant.now();
    }

    @Override
    public String toString() {
        return String.format("InstanceState{id=%s, addr=%s, status=%s, failures=%d}",
                instance.instanceId(), instance.address(),
                healthStatus.get(), consecutiveFailures.get());
    }
}

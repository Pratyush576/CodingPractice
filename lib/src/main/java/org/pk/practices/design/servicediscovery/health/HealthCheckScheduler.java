package org.pk.practices.design.servicediscovery.health;

import org.pk.practices.design.servicediscovery.model.InstanceState;
import org.pk.practices.design.servicediscovery.registry.ServiceRegistry;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Background engine that periodically runs health checks and updates the registry.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   ScheduledExecutorService
 *     └── every intervalMs: runChecks()
 *           ├── registry.getAll()          → list of all InstanceState objects
 *           └── for each instance:
 *                 ├── healthCheck.check()  → boolean
 *                 ├── PASS → markHealthy() + reset failures
 *                 └── FAIL → recordFailure()
 *                             if failures >= threshold → markUnhealthy()
 * </pre>
 *
 * <h2>Failure threshold</h2>
 * A single failed check does not immediately mark an instance unhealthy. The instance
 * must fail {@code unhealthyThreshold} consecutive checks before being pulled from
 * rotation. This prevents transient blips from causing unnecessary traffic shifts.
 *
 * <h2>Scaling to millions of instances</h2>
 * In production this would be sharded: each discovery node is responsible for a
 * partition of the keyspace (namespace/service prefix). The scheduled executor threads
 * would be replaced with a distributed check queue (Kafka topic per partition, workers
 * consuming in parallel). This in-memory implementation handles thousands of instances
 * per node comfortably at 2s intervals.
 */
public final class HealthCheckScheduler {

    private static final Logger log = Logger.getLogger(HealthCheckScheduler.class.getName());

    private final ServiceRegistry          registry;
    private final HealthCheck              healthCheck;
    private final int                      unhealthyThreshold;
    private final ScheduledExecutorService scheduler;

    /**
     * @param registry           the registry to read from and update
     * @param healthCheck        the strategy used to probe each instance
     * @param unhealthyThreshold number of consecutive failures before marking UNHEALTHY
     * @param threadCount        scheduler thread pool size
     */
    public HealthCheckScheduler(ServiceRegistry registry,
                                HealthCheck healthCheck,
                                int unhealthyThreshold,
                                int threadCount) {
        this.registry            = registry;
        this.healthCheck         = healthCheck;
        this.unhealthyThreshold  = unhealthyThreshold;
        this.scheduler           = Executors.newScheduledThreadPool(threadCount,
                r -> {
                    Thread t = new Thread(r, "health-check");
                    t.setDaemon(true);
                    return t;
                });
    }

    /** Convenience constructor with sensible defaults (threshold=2, threads=4). */
    public HealthCheckScheduler(ServiceRegistry registry, HealthCheck healthCheck) {
        this(registry, healthCheck, 2, 4);
    }

    /** Starts the scheduler, running checks every {@code intervalMs} milliseconds. */
    public void start(long intervalMs) {
        scheduler.scheduleAtFixedRate(this::runChecks, 0, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Health check scheduler started (interval=" + intervalMs + "ms, threshold="
                + unhealthyThreshold + ")");
    }

    /** Shuts down the scheduler and waits up to 5 seconds for running checks to finish. */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Core check loop ───────────────────────────────────────────────────────

    private void runChecks() {
        List<InstanceState> all = registry.getAll();
        for (InstanceState state : all) {
            try {
                checkInstance(state);
            } catch (Exception e) {
                log.warning("Health check threw for " + state.instance().instanceId()
                        + ": " + e.getMessage());
            }
        }
    }

    private void checkInstance(InstanceState state) {
        boolean healthy = healthCheck.check(state.instance());
        if (healthy) {
            registry.updateHealth(
                    state.instance().namespace(),
                    state.instance().serviceName(),
                    state.instance().instanceId(),
                    org.pk.practices.design.servicediscovery.model.HealthStatus.HEALTHY);
        } else {
            int failures = state.recordFailure();
            if (failures >= unhealthyThreshold) {
                registry.updateHealth(
                        state.instance().namespace(),
                        state.instance().serviceName(),
                        state.instance().instanceId(),
                        org.pk.practices.design.servicediscovery.model.HealthStatus.UNHEALTHY);
            }
        }
    }
}

package org.pk.practices.design.servicediscovery;

import org.pk.practices.design.servicediscovery.api.DefaultServiceDiscovery;
import org.pk.practices.design.servicediscovery.api.ServiceDiscovery;
import org.pk.practices.design.servicediscovery.dns.DnsResolver;
import org.pk.practices.design.servicediscovery.health.HeartbeatHealthCheck;
import org.pk.practices.design.servicediscovery.lb.RandomStrategy;
import org.pk.practices.design.servicediscovery.model.DnsRecord;
import org.pk.practices.design.servicediscovery.model.InstanceState;
import org.pk.practices.design.servicediscovery.model.ServiceInstance;
import org.pk.practices.design.servicediscovery.registry.InMemoryServiceRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * End-to-end demonstration of the Internal Service Discovery Platform.
 *
 * Scenario:
 *  1. Register user-service (5 instances), order-service (3), payment-service (2)
 *  2. Start health checker — heartbeat TTL = 3s, check interval = 1s
 *  3. Two user-service instances stop sending heartbeats → go UNHEALTHY
 *  4. Lookup returns only healthy subset
 *  5. DNS A/SRV resolution
 *  6. Round-robin and random load balancing
 *  7. Deregister one instance
 *  8. Print platform stats
 */
public final class ServiceDiscoveryDemo {

    private static final String NS = "production";

    public static void main(String[] args) throws InterruptedException {
        banner("SERVICE DISCOVERY PLATFORM DEMO");

        // ── 1. Build the platform ─────────────────────────────────────────────
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ServiceDiscovery discovery = DefaultServiceDiscovery.builder(
                        new HeartbeatHealthCheck(registry, Duration.ofSeconds(3)))
                .registry(registry)
                .checkIntervalMs(1_000)
                .unhealthyThreshold(1)   // fail fast for demo
                .dnsTtlSeconds(5)
                .build();

        // ── 2. Register instances ─────────────────────────────────────────────
        section("1. REGISTRATION");

        List<ServiceInstance> userInstances = List.of(
                instance("user-svc-1", "user-service", "10.0.1.1", 8080),
                instance("user-svc-2", "user-service", "10.0.1.2", 8080),
                instance("user-svc-3", "user-service", "10.0.1.3", 8080),
                instance("user-svc-4", "user-service", "10.0.1.4", 8080,
                        Map.of("canary", "true", "version", "2.1.0")),
                instance("user-svc-5", "user-service", "10.0.1.5", 8080)
        );

        List<ServiceInstance> orderInstances = List.of(
                instance("order-svc-1", "order-service", "10.0.2.1", 9090),
                instance("order-svc-2", "order-service", "10.0.2.2", 9090),
                instance("order-svc-3", "order-service", "10.0.2.3", 9090)
        );

        List<ServiceInstance> paymentInstances = List.of(
                instance("pay-svc-1", "payment-service", "10.0.3.1", 7070),
                instance("pay-svc-2", "payment-service", "10.0.3.2", 7070)
        );

        userInstances.forEach(discovery::register);
        orderInstances.forEach(discovery::register);
        paymentInstances.forEach(discovery::register);

        System.out.println("  Registered 5 user-service instances");
        System.out.println("  Registered 3 order-service instances");
        System.out.println("  Registered 2 payment-service instances");
        System.out.printf("  Total: %d instances%n", registry.totalCount());

        // ── 3. Start health checks + send heartbeats ──────────────────────────
        section("2. HEALTH CHECK LIFECYCLE");
        discovery.start();

        // Send heartbeats for all instances — they will become HEALTHY
        for (ServiceInstance inst : concat(userInstances, orderInstances, paymentInstances)) {
            discovery.heartbeat(NS, inst.serviceName(), inst.instanceId());
        }

        Thread.sleep(1_500);  // wait for first check cycle

        System.out.println("  After initial heartbeats:");
        printHealthSummary(discovery, "user-service");

        // Stop heartbeats for user-svc-4 and user-svc-5 → they go UNHEALTHY after TTL
        System.out.println();
        System.out.println("  Stopping heartbeats for user-svc-4 and user-svc-5 ...");
        System.out.println("  (TTL=3s, waiting 4s for them to expire)");
        Thread.sleep(4_000);

        // Keep all others alive
        for (ServiceInstance inst : List.of(
                userInstances.get(0), userInstances.get(1), userInstances.get(2))) {
            discovery.heartbeat(NS, inst.serviceName(), inst.instanceId());
        }
        orderInstances.forEach(i -> discovery.heartbeat(NS, i.serviceName(), i.instanceId()));
        paymentInstances.forEach(i -> discovery.heartbeat(NS, i.serviceName(), i.instanceId()));

        Thread.sleep(1_500);

        System.out.println();
        System.out.println("  After TTL expiry for 2 instances:");
        printHealthSummary(discovery, "user-service");

        // ── 4. Lookup ─────────────────────────────────────────────────────────
        section("3. LOOKUP");
        List<ServiceInstance> healthy = discovery.lookup(NS, "user-service");
        System.out.printf("  Healthy user-service instances (%d):%n", healthy.size());
        healthy.forEach(i -> System.out.printf("    %-15s %s  meta=%s%n",
                i.instanceId(), i.address(), i.metadata()));

        System.out.println();
        List<InstanceState> all = discovery.inspect(NS, "user-service");
        System.out.printf("  Full inspect (all %d instances including unhealthy):%n", all.size());
        all.forEach(s -> System.out.printf("    %-15s %-9s  failures=%d%n",
                s.instance().instanceId(), s.healthStatus(), s.consecutiveFailures()));

        // ── 5. DNS resolution ─────────────────────────────────────────────────
        section("4. DNS RESOLUTION");

        System.out.printf("  Query: %s%n", DnsResolver.aName(NS, "user-service"));
        List<DnsRecord> aRecords = discovery.resolveA(NS, "user-service");
        aRecords.forEach(r -> System.out.println("    " + r));

        System.out.println();
        System.out.printf("  Query: %s%n", DnsResolver.srvName(NS, "order-service"));
        List<DnsRecord> srvRecords = discovery.resolveSrv(NS, "order-service");
        srvRecords.forEach(r -> System.out.println("    " + r));

        // ── 6. Load balancing ─────────────────────────────────────────────────
        section("5. LOAD BALANCING");

        System.out.println("  Round-robin picks (10 requests to user-service):");
        for (int i = 0; i < 10; i++) {
            Optional<ServiceInstance> picked = discovery.pick(NS, "user-service");
            System.out.printf("    request %2d → %s%n", i + 1,
                    picked.map(ServiceInstance::instanceId).orElse("NONE"));
        }

        System.out.println();
        ServiceDiscovery randomDiscovery = DefaultServiceDiscovery.builder(
                        new HeartbeatHealthCheck(registry, Duration.ofSeconds(3)))
                .registry(registry)
                .lbStrategy(new RandomStrategy())
                .checkIntervalMs(60_000)  // don't start second scheduler
                .build();

        System.out.println("  Random picks (5 requests to order-service):");
        for (int i = 0; i < 5; i++) {
            Optional<ServiceInstance> picked = randomDiscovery.pick(NS, "order-service");
            System.out.printf("    request %d → %s%n", i + 1,
                    picked.map(ServiceInstance::instanceId).orElse("NONE"));
        }

        // ── 7. Deregistration ─────────────────────────────────────────────────
        section("6. DEREGISTRATION");
        System.out.println("  Deregistering order-svc-2 ...");
        discovery.deregister(NS, "order-service", "order-svc-2");
        List<ServiceInstance> afterDeregister = discovery.lookup(NS, "order-service");
        System.out.printf("  order-service healthy instances (%d): %s%n",
                afterDeregister.size(),
                afterDeregister.stream().map(ServiceInstance::instanceId).toList());

        // ── 8. Stats ──────────────────────────────────────────────────────────
        section("7. PLATFORM STATISTICS");
        discovery.stats().forEach((k, v) ->
                System.out.printf("  %-22s: %s%n", k, v));

        // ── Cleanup ───────────────────────────────────────────────────────────
        discovery.shutdown();
        System.out.println();
        System.out.println("  Platform shut down. Scheduler stopped.");
        System.out.println();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ServiceInstance instance(String id, String service, String host, int port) {
        return instance(id, service, host, port, Map.of());
    }

    private static ServiceInstance instance(String id, String service, String host, int port,
                                            Map<String, String> metadata) {
        return new ServiceInstance(id, service, NS, host, port, metadata, Instant.now());
    }

    private static void printHealthSummary(ServiceDiscovery discovery, String serviceName) {
        discovery.inspect(NS, serviceName).forEach(s ->
                System.out.printf("    %-15s %-9s%n",
                        s.instance().instanceId(), s.healthStatus()));
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T>... lists) {
        return java.util.Arrays.stream(lists)
                .flatMap(List::stream)
                .toList();
    }

    private static void banner(String title) {
        String line = "═".repeat(title.length() + 4);
        System.out.println("╔" + line + "╗");
        System.out.println("║  " + title + "  ║");
        System.out.println("╚" + line + "╝");
        System.out.println();
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("┌─ " + title + " " + "─".repeat(Math.max(0, 55 - title.length())));
    }
}

# Internal Service Discovery Platform

A hands-on implementation of a service discovery system in the spirit of AWS Cloud
Map / Consul / etcd-backed discovery — services **register** themselves, get
**health-checked**, and are **found** by peers via lookup, load-balanced pick, or
synthesised DNS records.

For the full architecture write-up (data model, scale strategy, CAP trade-offs,
failure scenarios, production gaps), see **[DESIGN.md](DESIGN.md)**.

---

## Package Layout

```
servicediscovery/
├── ServiceDiscoveryDemo.java            # Main entry point — end-to-end scenario
├── api/
│   ├── ServiceDiscovery.java            # Facade interface (Facade pattern)
│   └── DefaultServiceDiscovery.java     # Default wiring + Builder
├── registry/
│   ├── ServiceRegistry.java             # Storage interface (Repository pattern)
│   └── InMemoryServiceRegistry.java     # ConcurrentHashMap-backed implementation
├── health/
│   ├── HealthCheck.java                 # Strategy interface (functional)
│   ├── HeartbeatHealthCheck.java        # Passive: last-heartbeat vs. TTL
│   ├── TcpHealthCheck.java              # Active: TCP connect probe
│   └── HealthCheckScheduler.java        # Background polling + failure threshold
├── lb/
│   ├── LoadBalancingStrategy.java       # Strategy interface
│   ├── RoundRobinStrategy.java          # AtomicLong counter, even distribution
│   └── RandomStrategy.java              # ThreadLocalRandom, stateless
├── dns/
│   └── DnsResolver.java                 # Synthesises A / SRV records from live state
└── model/
    ├── ServiceInstance.java             # Immutable registration record
    ├── InstanceState.java               # Mutable runtime state (health, heartbeat)
    ├── HealthStatus.java                # HEALTHY / UNHEALTHY / UNKNOWN
    ├── DnsRecord.java                   # A / SRV record value object
    └── LookupQuery.java                 # Namespace + service (+ metadata filter)
```

---

## Quickstart

```java
InMemoryServiceRegistry registry = new InMemoryServiceRegistry();

ServiceDiscovery discovery = DefaultServiceDiscovery.builder(
                new HeartbeatHealthCheck(registry, Duration.ofSeconds(3)))
        .registry(registry)
        .checkIntervalMs(1_000)
        .unhealthyThreshold(2)
        .dnsTtlSeconds(10)
        .build();

discovery.start();

discovery.register(new ServiceInstance(
        "user-svc-1", "user-service", "production", "10.0.1.1", 8080,
        Map.of("version", "2.1.0"), Instant.now()));

discovery.heartbeat("production", "user-service", "user-svc-1");

List<ServiceInstance> healthy = discovery.lookup("production", "user-service");
Optional<ServiceInstance> target = discovery.pick("production", "user-service");
List<DnsRecord> aRecords = discovery.resolveA("production", "user-service");

discovery.shutdown();
```

### Run the full demo

```bash
./gradlew run
```

Walks through: registration → health check lifecycle (instances going
HEALTHY → UNHEALTHY on missed heartbeats) → lookup → DNS resolution →
round-robin / random load balancing → deregistration → stats.

---

## Extensibility

**New health check** — implement the functional interface and pass it to the builder:

```java
public class HttpHealthCheck implements HealthCheck {
    @Override
    public boolean check(ServiceInstance instance) {
        // GET http://{host}:{port}/health, return true on 2xx
    }
}
```

**New load balancing strategy** — implement `LoadBalancingStrategy` and inject via
`.lbStrategy(...)`:

```java
public class WeightedStrategy implements LoadBalancingStrategy {
    @Override public String name() { return "Weighted"; }
    @Override public Optional<ServiceInstance> pick(List<ServiceInstance> candidates) {
        // read "weight" from instance.metadata(), pick proportionally
    }
}
```

**New registry backend** — implement `ServiceRegistry` (e.g. an etcd-backed store)
and pass it via `.registry(...)`. `ServiceDiscovery` and the health/DNS/LB layers
are unaware of the storage implementation.

---

## Design Patterns Used

| Pattern | Where | Why |
|---|---|---|
| Strategy | `HealthCheck`, `LoadBalancingStrategy` | Swap check type / LB algorithm without touching the coordinator |
| Builder | `DefaultServiceDiscovery.Builder` | Many optional configuration knobs |
| Facade | `ServiceDiscovery` / `DefaultServiceDiscovery` | Single entry point hides registry + scheduler + resolver + LB |
| Repository | `ServiceRegistry` | Separates storage from business logic — swappable for etcd/Consul |
| Immutable value object | `ServiceInstance`, `DnsRecord`, `LookupQuery` | Thread-safe sharing without locking |

See [DESIGN.md § 11](DESIGN.md#11-design-patterns-used) for the full rationale.

---

## Concurrency Model

- **Registry**: nested `ConcurrentHashMap<namespace, ConcurrentHashMap<service, ConcurrentHashMap<instanceId, InstanceState>>>`; writes use `computeIfAbsent` to avoid explicit locking.
- **`InstanceState`**: `healthStatus` is an `AtomicReference` (CAS swap), `lastHeartbeat`/`lastChecked` are `volatile` (single-writer visibility), `consecutiveFailures` is an `AtomicInteger`.
- **`HealthCheckScheduler`**: runs on a daemon thread pool via `scheduleAtFixedRate`, so `ServiceDiscovery.shutdown()` isn't strictly required for JVM exit — but always call it to stop probing promptly.

All `ServiceDiscovery` methods are safe to call from multiple threads concurrently.

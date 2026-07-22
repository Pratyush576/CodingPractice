# Internal Service Discovery Platform — Design Document

**Use case**: An internal platform analogous to AWS Cloud Map — enabling microservices to
register themselves, be health-checked, and be found by peers via lookup or DNS. Target
scale: **millions of instances** across thousands of services.

---

## Table of Contents

1. [Requirements](#1-requirements)
2. [High-Level Architecture](#2-high-level-architecture)
3. [Data Model](#3-data-model)
4. [Component Deep Dives](#4-component-deep-dives)
5. [Scale Strategy](#5-scale-strategy)
6. [Consistency & Availability Trade-offs](#6-consistency--availability-trade-offs)
7. [Health Check Engine](#7-health-check-engine)
8. [DNS Integration](#8-dns-integration)
9. [Load Balancing](#9-load-balancing)
10. [Failure Scenarios](#10-failure-scenarios)
11. [Design Patterns Used](#11-design-patterns-used)
12. [In-Memory vs. Production Gaps](#12-in-memory-vs-production-gaps)

---

## 1. Requirements

### Functional
| Capability      | Description                                                          |
|-----------------|----------------------------------------------------------------------|
| Register        | Service instance announces itself (host, port, metadata, namespace) |
| Deregister      | Explicit removal on graceful shutdown                                |
| Heartbeat       | Periodic keepalive to reset the TTL clock                           |
| Lookup          | Return healthy instances for a given namespace + service name        |
| Health checks   | Passive (heartbeat TTL) and active (TCP/HTTP probe)                 |
| DNS support     | Synthesise A and SRV records from the live registry                 |
| Namespacing     | Logical isolation (e.g., production / staging / tenant-xyz)         |
| Metadata filter | Route by version, canary flag, availability zone, etc.              |

### Non-Functional
- **Scale**: millions of instances, thousands of services
- **Latency**: lookup P99 < 5 ms; DNS query < 10 ms
- **Availability**: 99.99% uptime; control plane failures must not block data plane
- **Consistency**: eventual — stale data for up to TTL seconds is acceptable
- **Throughput**: 100k registrations/sec, 1M lookups/sec per cluster

---

## 2. High-Level Architecture

```
 Clients (services)
       │
       │  register / deregister / heartbeat / lookup
       ▼
 ┌──────────────────────────────────────────────────────────┐
 │              Service Discovery API Layer                  │
 │         (DefaultServiceDiscovery / SDK / REST)           │
 └──────┬──────────────┬───────────────┬────────────────────┘
        │              │               │
        ▼              ▼               ▼
 ┌──────────┐  ┌──────────────┐  ┌──────────────────────┐
 │ Registry │  │ Health Check │  │     DNS Resolver      │
 │  Storage │  │  Scheduler   │  │  (A records, SRV)     │
 └──────────┘  └──────────────┘  └──────────────────────┘
        │
        ▼
 ┌──────────────────────────────────────────────────────────┐
 │          Load Balancing (RoundRobin / Random / ...)       │
 └──────────────────────────────────────────────────────────┘
```

### Production cluster layout (3+ regions)

```
  Region A                    Region B                    Region C
  ┌──────────────┐            ┌──────────────┐            ┌──────────────┐
  │ Discovery    │◄──Raft────►│ Discovery    │◄──Raft────►│ Discovery    │
  │ Cluster x3   │            │ Cluster x3   │            │ Cluster x3   │
  └──────────────┘            └──────────────┘            └──────────────┘
         │                          │                           │
         └─────────────────── Global DNS ──────────────────────┘
```

---

## 3. Data Model

### Hierarchy

```
Namespace  (e.g., "production", "staging", "tenant-acme")
  └── Service  (e.g., "user-service", "order-service")
        └── Instance  (e.g., "user-svc-1")
              ├── Registration data (immutable after register)
              │     host, port, metadata, registeredAt
              └── Runtime state (mutable, health-engine driven)
                    healthStatus, lastHeartbeat, lastChecked, consecutiveFailures
```

### Why split ServiceInstance and InstanceState?

```
ServiceInstance (record — immutable)          InstanceState (class — mutable)
  instanceId                                    AtomicReference<HealthStatus>
  serviceName                                   volatile Instant lastHeartbeat
  namespace                                     volatile Instant lastChecked
  host, port                                    AtomicInteger consecutiveFailures
  metadata
  registeredAt
```

Keeping the registration record immutable avoids the need to serialise/deserialise it
on every health check update. The registry can swap the health status without touching
the registration data. In a distributed system, health state is volatile gossip-propagated
data while registration data is durably stored in the KV store.

### Key structure

```
{namespace} / {serviceName} / {instanceId}  →  InstanceState
```

This three-level key structure enables:
- Full scan of a namespace for monitoring
- Fast lookup by service (most common query path)
- Direct get for heartbeat / health update

---

## 4. Component Deep Dives

### 4.1 ServiceRegistry

The storage interface is pure CRUD. The in-memory implementation uses:

```
ConcurrentHashMap<namespace,
  ConcurrentHashMap<serviceName,
    ConcurrentHashMap<instanceId, InstanceState>>>
```

**Write path** (register): `computeIfAbsent` at each level ensures maps are created
exactly once under concurrent load without explicit locking.

**Read path** (lookup): Iterates `values()` of the service map. `ConcurrentHashMap.values()`
returns a weakly consistent view — reads proceed without blocking writes.

**Deregister with pruning**: After removing an instance, if the service map is empty it
is removed to prevent unbounded memory growth over time.

### 4.2 InstanceState Thread Safety

| Field               | Concurrency mechanism   | Reason                                    |
|---------------------|-------------------------|-------------------------------------------|
| healthStatus        | AtomicReference         | CAS swap — no lost updates under contention|
| lastHeartbeat       | volatile                | Single writer (heartbeat caller); volatile ensures visibility to all readers |
| lastChecked         | volatile                | Single writer (health scheduler); same as above |
| consecutiveFailures | AtomicInteger           | Incremented by health thread, reset on recovery |

### 4.3 Health Check Scheduler

```
start(intervalMs)
  └── scheduleAtFixedRate → runChecks()
        └── registry.getAll()
              └── for each InstanceState:
                    healthCheck.check(instance)
                    ├── true  → markHealthy(), reset failures
                    └── false → recordFailure()
                                if failures >= threshold → markUnhealthy()
```

**Failure threshold** prevents a single transient failure from pulling an instance out
of rotation. Default: 2 consecutive failures → UNHEALTHY.

**Thread pool**: daemon threads so the JVM can exit cleanly without `shutdown()`.

### 4.4 DNS Resolver

Records are synthesised on-demand from the live registry, not cached. This means DNS
queries always reflect the current health state (minus the client-side TTL cache).

```
A record:    user-service.production.svc.discovery → 10.0.1.1
             user-service.production.svc.discovery → 10.0.1.2  (multi-A)
SRV record:  _user-service._tcp.production.svc.discovery → 10.0.1.1:8080 pri=10 wt=10
```

### 4.5 Load Balancing

| Strategy    | State             | Use case                                   |
|-------------|-------------------|--------------------------------------------|
| RoundRobin  | AtomicLong counter| General purpose, even distribution         |
| Random      | ThreadLocalRandom | Fan-out, cache, stateless callers          |
| WeightedRR  | (not shown)       | Canary: route 5% to new version via weight |
| ConsistHash | (not shown)       | Session affinity, request coalescing       |

---

## 5. Scale Strategy

### 5.1 Partitioning (sharding)

At millions of instances, a single node cannot hold all state in memory. Shard by namespace:

```
shard_id = hash(namespace) % num_nodes
```

Each shard node owns a partition of the namespace space. Clients route to the correct
shard using consistent hashing (no central coordinator needed for routing).

### 5.2 Distributed storage

Replace `InMemoryServiceRegistry` with an etcd-backed implementation:

```
etcd key:   /discovery/{namespace}/{service}/{instanceId}
etcd value: JSON(ServiceInstance) + lease_id

Lease ID: auto-expiring lease attached to each key. If the service crashes without
deregistering, etcd expires the key when the lease TTL passes.
```

**etcd watch** enables push-based invalidation: discovery nodes subscribe to
`/discovery/{namespace}/*` and update their local cache when instances change.
This eliminates polling and reduces propagation latency from O(check_interval) to O(ms).

### 5.3 Caching layer

```
Client SDK
  └── local cache (TTL = 10s)
        ├── HIT: serve from cache (0 ms)
        └── MISS: query discovery cluster → update cache
```

Local caching absorbs 99%+ of lookup traffic. The discovery cluster only receives
cache misses and watch-triggered invalidations.

### 5.4 Read replicas

Separate write-optimised leader nodes from read-optimised follower nodes. Followers
receive replication from the leader and serve all lookup / DNS queries. Only register /
deregister hit the leader.

---

## 6. Consistency & Availability Trade-offs

### CAP theorem position

This platform is **AP (Available + Partition-tolerant)**:
- During network partition, nodes serve stale (pre-partition) data rather than failing
- Consistency is eventual: data converges when the partition heals
- TTL bounds the staleness window

### Acceptable staleness

```
Max staleness = max(DNS TTL, heartbeat TTL, replication lag)
              ≈ max(10s, 3s, 50ms) = 10s (DNS-dominated)
```

For most microservice traffic, 10 seconds of stale routing is acceptable. For strict
consistency (e.g., auth token validation), use a direct registry read, not cached DNS.

### Failure modes

| Failure              | Impact                                      | Mitigation                         |
|----------------------|---------------------------------------------|------------------------------------|
| Discovery node crash | Clients use cached data                     | Multiple replicas behind VIP/LB    |
| Network partition    | Stale lookups; new registrations drop       | Client-side retry + fallback cache |
| etcd leader election | 100–500ms write pause                       | Read replicas unaffected           |
| Health check threads slow | Slow convergence to UNHEALTHY         | Larger thread pool; async checks   |
| DNS TTL too high     | Traffic to dead instances for up to TTL     | Lower TTL (5–10s) for critical svcs|

---

## 7. Health Check Engine

### Check types and selection guide

```
┌─────────────────┬──────────────────────────────────────────────────────────┐
│ Type            │ Use when                                                  │
├─────────────────┼──────────────────────────────────────────────────────────┤
│ Heartbeat TTL   │ Instance controls its own heartbeat; works behind NAT     │
│ TCP connect     │ Port must be open; no app-level health guarantee          │
│ HTTP /health    │ App-level readiness (connection pool ready, migrations done│
│ gRPC Health     │ gRPC services; standard Health Checking Protocol          │
│ Command exec    │ Custom scripts, legacy systems                            │
└─────────────────┴──────────────────────────────────────────────────────────┘
```

### Defence-in-depth (recommended for production)

Layer multiple checks: heartbeat TTL as the fast path (catches crash), TCP connect as
the secondary path (catches zombie processes holding the port), HTTP /health as the deep
check (catches deadlocked threads, full queues, dependency failures).

### Failure threshold algorithm

```
check_result = probe(instance)
if check_result == PASS:
    consecutive_failures = 0
    status = HEALTHY
else:
    consecutive_failures += 1
    if consecutive_failures >= threshold:
        status = UNHEALTHY
    // else: still HEALTHY — transient blip ignored
```

Hysteresis: require N successive successes before recovering from UNHEALTHY (prevents
flapping). Not shown in this implementation but easy to add with a `consecutiveSuccesses`
AtomicInteger.

---

## 8. DNS Integration

### Domain naming convention

```
A record:    {service}.{namespace}.svc.discovery
SRV record:  _{service}._tcp.{namespace}.svc.discovery
```

### Multi-A record load balancing

Returning all healthy IPs in an A record lets clients use OS-level random selection
(or their HTTP client's built-in LB). Combined with a low TTL this spreads load evenly
without requiring client-side LB logic.

### SRV record priority / weight

SRV records carry routing hints that allow clients to implement:
- **Priority groups**: primary instances (priority=10) are tried before standby (priority=20)
- **Weighted routing**: canary instance at weight=5 receives 5/(5+95)=5% of traffic

### Integration path for CoreDNS

```yaml
# Corefile
.:53 {
    forward . /etc/resolv.conf
}

svc.discovery:53 {
    grpc . localhost:9053   # discovery platform exposes gRPC DNS backend
    log
    cache 10
}
```

The discovery platform implements the CoreDNS `external` gRPC plugin protocol, so
CoreDNS forwards `.svc.discovery` queries to the platform and caches responses.

---

## 9. Load Balancing

### Where to balance

```
Option A: Server-side (at the discovery platform)
  Client calls pick() → platform chooses one instance → client gets endpoint

Option B: Client-side (in the SDK)
  Client calls lookup() → gets list → SDK applies LB locally → no round-trip for pick

Option C: L4/L7 proxy (Envoy, NGINX)
  Client connects to proxy → proxy balances across discovered backends
```

This implementation provides **Option A** via `pick()` and **Option B** via `lookup()`.
Option C is the most production-common pattern — the discovery platform integrates
with Envoy via xDS API or with NGINX via the NGINX Plus Service Discovery module.

### Consistent hashing (not shown — design sketch)

```java
// Key idea: map the request onto a hash ring
long requestHash = hash(sessionId or userId);
ServiceInstance pick = ring.higher(requestHash);  // clockwise lookup
// same caller → same instance → warm local cache on target
```

Use when: stateful services where the same caller must reach the same backend
(e.g., session stores, per-user caches, WebSocket connections).

---

## 10. Failure Scenarios

### Instance crash (no graceful deregister)

```
t=0s   Instance crashes — no deregister call
t=3s   Heartbeat TTL expires → healthStatus = UNKNOWN
t=4s   Next health check cycle → consecutive_failures=1 → still unknown
t=5s   Second check → consecutive_failures=2 → UNHEALTHY
t=5s   lookup() stops returning this instance
t=5s   DNS records updated (next query after TTL=10s picks up the change)
```

**Total outage window**: up to 5s for lookup + 10s for DNS-cached clients = 15s max.

### Discovery cluster node failure

```
Client SDK has local cache → serves traffic from cache for 10s
Remaining cluster nodes take over → no gap if 2+ nodes healthy
Client reconnects to next node on cache miss
```

### Split-brain during network partition

```
Partition: Node A sees {instances 1,2,3}; Node B sees {instances 4,5}
During partition: clients on each side see different subsets (both available!)
After partition heals: Raft consensus reconciles state; minority side discards divergent writes
```

For service discovery (eventual consistency tolerated), partition-time divergence is
acceptable. Registration on the minority side may need to be re-submitted after healing.

---

## 11. Design Patterns Used

| Pattern           | Where                             | Why                                                       |
|-------------------|-----------------------------------|-----------------------------------------------------------|
| **Strategy**      | `HealthCheck`, `LoadBalancingStrategy` | Swap check type / LB algorithm without changing coordinator |
| **Builder**       | `DefaultServiceDiscovery.Builder` | Many optional configuration parameters                    |
| **Facade**        | `DefaultServiceDiscovery` / `ServiceDiscovery` | Single entry point hides internal component graph |
| **Repository**    | `ServiceRegistry`                 | Separates data access from business logic                 |
| **Observer/Watch**| (production: etcd watch)          | Push invalidation instead of polling                      |
| **Immutable VO**  | `ServiceInstance`, `DnsRecord`, `LookupQuery` | Thread-safe sharing without locking           |
| **Composite check** | Layer Heartbeat + TCP + HTTP    | Defence-in-depth; each layer catches different failure mode |

---

## 12. In-Memory vs. Production Gaps

| Concern                  | This implementation          | Production implementation                 |
|--------------------------|------------------------------|-------------------------------------------|
| Storage                  | ConcurrentHashMap            | etcd / Consul KV with Raft replication    |
| Persistence              | None (lost on JVM restart)   | etcd WAL + snapshots                      |
| Crash detection TTL      | Heartbeat check in scheduler | etcd lease auto-expiry                    |
| Change propagation       | Polling (check interval)     | etcd watch → pub/sub → near-instant       |
| Scale                    | Single JVM, ~100k instances  | Sharded cluster, millions of instances    |
| Multi-region             | None                         | Raft groups per region; global DNS        |
| Authentication           | None                         | mTLS between instances and discovery      |
| Audit log                | None                         | Append-only event log per namespace       |
| Metrics                  | stats() map                  | Prometheus metrics, Grafana dashboards    |
| DNS server               | Resolver class (no server)   | CoreDNS plugin or PowerDNS backend        |
| Service mesh integration | None                         | xDS API (Envoy), SMI (service mesh APIs)  |

---

## Running the Demo

```bash
# From the CodingPractice root
./gradlew run
```

Expected output sequence:
1. Registration of 10 instances across 3 services
2. Health check startup — all instances become HEALTHY via heartbeat
3. 2 user-service instances stop heartbeating → become UNHEALTHY
4. Lookup returns only 3 healthy user-service instances
5. DNS A records for user-service (3 IPs)
6. DNS SRV records for order-service (3 endpoints with port 9090)
7. Round-robin picks cycling through healthy instances
8. Random picks for order-service
9. Deregistration of one order-service instance
10. Final statistics dashboard

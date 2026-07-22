package org.pk.practices.design.servicediscovery.model;

/**
 * A DNS resource record synthesised from the service registry.
 *
 * <h2>Record types</h2>
 * <ul>
 *   <li><b>A record</b> — maps a service name to an IPv4 address.
 *       Query: {@code user-service.production.svc.discovery → 10.0.1.5}</li>
 *   <li><b>SRV record</b> — maps a service name to a full endpoint descriptor
 *       (host, port, priority, weight). Required when the port is not well-known.
 *       Query: {@code _user-service._tcp.production.svc.discovery → 10.0.1.5:8080}</li>
 * </ul>
 *
 * <h2>Priority and weight (SRV)</h2>
 * Clients try the lowest-priority record first. Among equal-priority records,
 * weight determines the fraction of traffic each endpoint receives:
 * {@code traffic_fraction = weight / sum(all weights at same priority)}.
 *
 * <h2>TTL</h2>
 * Clients and resolvers cache this record for {@code ttlSeconds}. Shorter TTLs
 * mean faster propagation of changes (deregistration, health changes) at the cost
 * of more DNS queries. Typical values: 5–30 seconds for service discovery.
 */
public record DnsRecord(
        Type   type,
        String name,          // DNS name queried (e.g. "user-service.production.svc.discovery")
        String target,        // A: IP address; SRV: hostname
        int    port,          // A: 0 (not applicable); SRV: service port
        int    priority,      // SRV: lower number = higher priority; 0 for A records
        int    weight,        // SRV: proportional traffic weight; 0 for A records
        int    ttlSeconds) {

    public enum Type { A, SRV }

    /** Creates an A record mapping a DNS name to an IPv4 address. */
    public static DnsRecord aRecord(String name, String ip, int ttlSeconds) {
        return new DnsRecord(Type.A, name, ip, 0, 0, 0, ttlSeconds);
    }

    /** Creates an SRV record for a named service endpoint with routing hints. */
    public static DnsRecord srvRecord(String name, String host, int port,
                                      int priority, int weight, int ttlSeconds) {
        return new DnsRecord(Type.SRV, name, host, port, priority, weight, ttlSeconds);
    }

    @Override
    public String toString() {
        return type == Type.A
                ? String.format("A    %s → %s  (ttl=%ds)", name, target, ttlSeconds)
                : String.format("SRV  %s → %s:%d  pri=%d wt=%d  (ttl=%ds)",
                        name, target, port, priority, weight, ttlSeconds);
    }
}

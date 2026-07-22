package org.pk.practices.design.servicediscovery.dns;

import org.pk.practices.design.servicediscovery.model.DnsRecord;
import org.pk.practices.design.servicediscovery.model.HealthStatus;
import org.pk.practices.design.servicediscovery.model.InstanceState;
import org.pk.practices.design.servicediscovery.registry.ServiceRegistry;

import java.util.List;

/**
 * Synthesises DNS records from the live service registry.
 *
 * <h2>DNS name format</h2>
 * <pre>
 *   A record:   {serviceName}.{namespace}.svc.discovery
 *   SRV record: _{serviceName}._tcp.{namespace}.svc.discovery
 * </pre>
 *
 * <h2>Why synthetic DNS?</h2>
 * DNS is the lowest-common-denominator discovery protocol: every language, runtime,
 * and infrastructure component can query it. By projecting the registry into DNS,
 * services that don't integrate the discovery SDK can still find their dependencies.
 *
 * <h2>Filtering</h2>
 * Only HEALTHY instances are projected into DNS records. Clients that cache the
 * answer set will continue routing to the last-known healthy set for up to {@code TTL}
 * seconds after an instance fails — a deliberate availability-vs-accuracy tradeoff.
 * Set {@code ttlSeconds} low (5–30) for faster convergence at the cost of more queries.
 *
 * <h2>In production</h2>
 * This resolver would be embedded in a custom DNS server (CoreDNS plugin, PowerDNS
 * backend, or BIND DLZ) or exposed via the RFC 8484 DNS-over-HTTPS endpoint. Responses
 * would be signed with DNSSEC for integrity. Zone transfers would be replaced by
 * registry change-event subscriptions for near-zero propagation delay.
 */
public final class DnsResolver {

    private static final String DOMAIN_SUFFIX = "svc.discovery";
    private static final int    DEFAULT_TTL   = 10;   // seconds

    private final ServiceRegistry registry;
    private final int             ttlSeconds;

    public DnsResolver(ServiceRegistry registry, int ttlSeconds) {
        this.registry   = registry;
        this.ttlSeconds = ttlSeconds;
    }

    public DnsResolver(ServiceRegistry registry) {
        this(registry, DEFAULT_TTL);
    }

    /**
     * Resolves A records for all healthy instances of a service.
     * Returns one A record per healthy IP address.
     */
    public List<DnsRecord> resolveA(String namespace, String serviceName) {
        String dnsName = aName(namespace, serviceName);
        return healthyInstances(namespace, serviceName).stream()
                .map(s -> DnsRecord.aRecord(dnsName, s.instance().host(), ttlSeconds))
                .toList();
    }

    /**
     * Resolves SRV records for all healthy instances of a service.
     * Each SRV record carries the full host:port plus priority/weight for client-side
     * load balancing. Priority is uniform (10); weight is uniform (10) — callers that
     * need weighted routing should use the {@code LoadBalancingStrategy} instead.
     */
    public List<DnsRecord> resolveSrv(String namespace, String serviceName) {
        String dnsName = srvName(namespace, serviceName);
        return healthyInstances(namespace, serviceName).stream()
                .map(s -> DnsRecord.srvRecord(
                        dnsName,
                        s.instance().host(),
                        s.instance().port(),
                        10,  // priority — equal for all
                        10,  // weight — equal for all; override via metadata in production
                        ttlSeconds))
                .toList();
    }

    // ── Name helpers ──────────────────────────────────────────────────────────

    public static String aName(String namespace, String serviceName) {
        return serviceName + "." + namespace + "." + DOMAIN_SUFFIX;
    }

    public static String srvName(String namespace, String serviceName) {
        return "_" + serviceName + "._tcp." + namespace + "." + DOMAIN_SUFFIX;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private List<InstanceState> healthyInstances(String namespace, String serviceName) {
        return registry.getInstances(namespace, serviceName).stream()
                .filter(s -> s.healthStatus() == HealthStatus.HEALTHY)
                .toList();
    }
}

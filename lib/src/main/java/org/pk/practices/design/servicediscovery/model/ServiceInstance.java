package org.pk.practices.design.servicediscovery.model;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable snapshot of a single registered service instance.
 *
 * <h2>Identity</h2>
 * An instance is uniquely identified by the triple ({@code namespace}, {@code serviceName},
 * {@code instanceId}). Two instances with the same triple are considered the same endpoint
 * and a re-registration overwrites the prior entry.
 *
 * <h2>Namespace</h2>
 * Namespaces isolate services across environments (production, staging) or teams.
 * They map directly to DNS zones: {@code {service}.{namespace}.svc.discovery}.
 *
 * <h2>Metadata</h2>
 * Arbitrary key-value tags that callers can filter on during lookup. Typical uses:
 * version labels ({@code "version" -> "2.1.0"}), region tags, or canary flags.
 */
public record ServiceInstance(
        String              instanceId,
        String              serviceName,
        String              namespace,
        String              host,
        int                 port,
        Map<String, String> metadata,
        Instant             registeredAt) {

    /** Returns the network address in {@code host:port} form. */
    public String address() {
        return host + ":" + port;
    }

    /** Composite key used as the registry lookup path. */
    public String registryKey() {
        return namespace + "/" + serviceName + "/" + instanceId;
    }

    @Override
    public String toString() {
        return String.format("ServiceInstance{id=%s, service=%s/%s, addr=%s, meta=%s}",
                instanceId, namespace, serviceName, address(), metadata);
    }
}

package org.pk.practices.design.servicediscovery.model;

import java.util.Map;

/**
 * Parameters for a service instance lookup.
 *
 * <h2>Filtering</h2>
 * <ul>
 *   <li>{@code healthyOnly} (default {@code true}) — exclude UNHEALTHY instances.
 *       Set to {@code false} to inspect all registered instances including sick ones.</li>
 *   <li>{@code metadataFilter} — only return instances whose metadata contains all
 *       specified key-value pairs. Useful for canary routing ({@code "canary" → "true"}),
 *       version pinning ({@code "version" → "2.1.0"}), or region affinity.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   // Simple: healthy instances of a service
 *   LookupQuery.of("production", "user-service")
 *
 *   // Only canary instances
 *   LookupQuery.builder("production", "user-service")
 *              .metadataFilter(Map.of("canary", "true"))
 *              .build()
 *
 *   // All instances including unhealthy (for debugging)
 *   LookupQuery.builder("production", "user-service")
 *              .healthyOnly(false)
 *              .build()
 * </pre>
 */
public record LookupQuery(
        String              namespace,
        String              serviceName,
        boolean             healthyOnly,
        Map<String, String> metadataFilter) {

    /** Convenience factory: healthy-only, no metadata filter. */
    public static LookupQuery of(String namespace, String serviceName) {
        return new LookupQuery(namespace, serviceName, true, Map.of());
    }

    public static Builder builder(String namespace, String serviceName) {
        return new Builder(namespace, serviceName);
    }

    public static final class Builder {
        private final String namespace;
        private final String serviceName;
        private boolean             healthyOnly    = true;
        private Map<String, String> metadataFilter = Map.of();

        private Builder(String namespace, String serviceName) {
            this.namespace   = namespace;
            this.serviceName = serviceName;
        }

        public Builder healthyOnly(boolean v)             { this.healthyOnly    = v; return this; }
        public Builder metadataFilter(Map<String, String> m) { this.metadataFilter = m; return this; }
        public LookupQuery build() {
            return new LookupQuery(namespace, serviceName, healthyOnly, metadataFilter);
        }
    }
}

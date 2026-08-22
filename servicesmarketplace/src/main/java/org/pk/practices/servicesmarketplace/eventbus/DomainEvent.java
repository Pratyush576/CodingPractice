package org.pk.practices.servicesmarketplace.eventbus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** DESIGN.md §2's canonical event shape, in-process only (no Kafka in this build). */
public record DomainEvent(UUID eventId, String eventType, String entityId, Map<String, Object> payload, Instant occurredAt) {

    public static DomainEvent of(String eventType, String entityId, Map<String, Object> payload) {
        return new DomainEvent(UUID.randomUUID(), eventType, entityId, payload, Instant.now());
    }
}

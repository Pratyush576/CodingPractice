package org.pk.practices.cabreservation.eventbus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** DESIGN.md §2/§5's canonical event shape, in-process only (no Kafka in this build). */
public record DomainEvent(UUID eventId, String eventType, String tripId, Map<String, Object> payload, Instant occurredAt) {

    public static DomainEvent of(String eventType, String tripId, Map<String, Object> payload) {
        return new DomainEvent(UUID.randomUUID(), eventType, tripId, payload, Instant.now());
    }
}

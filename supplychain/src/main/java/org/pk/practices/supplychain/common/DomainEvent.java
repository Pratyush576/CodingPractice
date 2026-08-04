package org.pk.practices.supplychain.common;

import java.util.Map;
import java.util.UUID;

/**
 * The shape every {@code Publish, don't call} emission takes before it's
 * written to the transactional outbox. See LLD.md §1.3 Consistency Mechanisms.
 */
public record DomainEvent(
        String eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        Map<String, Object> payload
) {
    public static DomainEvent of(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        return new DomainEvent(UUID.randomUUID().toString(), aggregateType, aggregateId, eventType, payload);
    }
}

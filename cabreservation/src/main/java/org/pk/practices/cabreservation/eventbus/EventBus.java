package org.pk.practices.cabreservation.eventbus;

import java.util.function.Consumer;

/**
 * In-process pub/sub for the POC (DESIGN.md §12) — publish() must never
 * block the caller, which is what makes TripService.requestTrip()'s
 * "immediate ack, not the match result" (§4.1) actually true.
 */
public interface EventBus {
    void subscribe(String eventType, Consumer<DomainEvent> handler);
    void publish(DomainEvent event);
}

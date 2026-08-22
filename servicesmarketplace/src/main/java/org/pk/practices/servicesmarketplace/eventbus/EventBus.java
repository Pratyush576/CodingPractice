package org.pk.practices.servicesmarketplace.eventbus;

import java.util.function.Consumer;

/**
 * In-process pub/sub for the POC (DESIGN.md §2/§9) — publish() must never
 * block the caller, which is what makes RequestService.postRequest()'s
 * "insert and return, don't wait on matching" shape actually true.
 */
public interface EventBus {
    void subscribe(String eventType, Consumer<DomainEvent> handler);
    void publish(DomainEvent event);
}

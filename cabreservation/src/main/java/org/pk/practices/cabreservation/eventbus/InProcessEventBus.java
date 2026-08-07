package org.pk.practices.cabreservation.eventbus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * DESIGN.md §12 — in-process pub/sub for the POC (no Kafka). publish()
 * hands off to a background executor and returns immediately, which is
 * what makes TripService.requestTrip()'s "immediate ack, not the match
 * result" (§4.1) actually true — the publisher never blocks on a subscriber.
 */
public class InProcessEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventBus.class);

    private final Map<String, List<Consumer<DomainEvent>>> handlersByType = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @Override
    public void subscribe(String eventType, Consumer<DomainEvent> handler) {
        handlersByType.computeIfAbsent(eventType, t -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public void publish(DomainEvent event) {
        List<Consumer<DomainEvent>> handlers = handlersByType.get(event.eventType());
        if (handlers == null || handlers.isEmpty()) {
            log.debug("No subscribers for {} (tripId={}) — dropped", event.eventType(), event.tripId());
            return;
        }
        for (Consumer<DomainEvent> handler : handlers) {
            executor.submit(() -> {
                try {
                    handler.accept(event);
                } catch (Exception e) {
                    log.error("Handler failed for {} (tripId={})", event.eventType(), event.tripId(), e);
                }
            });
        }
    }

    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

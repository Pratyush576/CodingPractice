package org.pk.practices.servicesmarketplace.request;

import org.pk.practices.servicesmarketplace.common.AuthorizationException;
import org.pk.practices.servicesmarketplace.common.ConflictException;
import org.pk.practices.servicesmarketplace.common.DomainException;
import org.pk.practices.servicesmarketplace.eventbus.DomainEvent;
import org.pk.practices.servicesmarketplace.eventbus.EventBus;
import org.pk.practices.servicesmarketplace.eventbus.EventTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * DESIGN.md §4.2/§4.4. {@code postRequest} inserts and publishes, then
 * returns immediately — it never waits on matching, the same
 * "insert and return, don't wait on the reaction" shape used throughout
 * this design.
 */
public class RequestService {

    private final RequestRepository requestRepository;
    private final EventBus eventBus;

    public RequestService(RequestRepository requestRepository, EventBus eventBus) {
        this.requestRepository = requestRepository;
        this.eventBus = eventBus;
    }

    public Request postRequest(String customerId, String categoryId, String answersJson, double lat, double lng, String desiredTiming) {
        Request request = new Request(
                UUID.randomUUID().toString(),
                customerId,
                categoryId,
                answersJson,
                lat, lng,
                desiredTiming,
                RequestStatus.OPEN,
                null,
                Instant.now()
        );
        requestRepository.insert(request);
        eventBus.publish(DomainEvent.of(EventTypes.REQUEST_POSTED, request.requestId(), Map.of()));
        return request;
    }

    public Optional<Request> get(String requestId) {
        return requestRepository.find(requestId);
    }

    public List<Request> listForCustomer(String customerId) {
        return requestRepository.findByCustomer(customerId);
    }

    /** DESIGN.md §4.4's hire CAS — a lost race throws {@link ConflictException}, not a silent overwrite. */
    public Request hire(String requestId, String quoteId, String customerId) {
        Request request = requestRepository.find(requestId)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "No request with id " + requestId));
        if (!request.customerId().equals(customerId)) {
            throw new AuthorizationException("This request does not belong to you");
        }
        if (!requestRepository.hire(requestId, quoteId)) {
            throw new ConflictException("Request " + requestId + " is no longer OPEN — reload and retry");
        }
        eventBus.publish(DomainEvent.of(EventTypes.REQUEST_HIRED, requestId, Map.of("quoteId", quoteId)));
        return requestRepository.find(requestId).orElseThrow();
    }
}

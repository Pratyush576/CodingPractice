package org.pk.practices.servicesmarketplace.matching;

import org.pk.practices.servicesmarketplace.eventbus.DomainEvent;
import org.pk.practices.servicesmarketplace.eventbus.EventBus;
import org.pk.practices.servicesmarketplace.eventbus.EventTypes;
import org.pk.practices.servicesmarketplace.lead.Lead;
import org.pk.practices.servicesmarketplace.lead.LeadRepository;
import org.pk.practices.servicesmarketplace.lead.LeadStatus;
import org.pk.practices.servicesmarketplace.pro.ProProfile;
import org.pk.practices.servicesmarketplace.pro.ProRepository;
import org.pk.practices.servicesmarketplace.request.Request;
import org.pk.practices.servicesmarketplace.request.RequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DESIGN.md §4.2 — broadcast, not single-assignment: every Pro who matches
 * gets their own {@link Lead} row, unlike a dispatch system's single winner.
 * There's no offer-timeout/redispatch sweeper here (unlike a trip offer) —
 * a delivered Lead just sits {@code DELIVERED} until unlocked or the
 * Request closes.
 */
public class MatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);

    /**
     * DESIGN.md §4.6 describes Lead pricing as dynamic (job value, urgency,
     * competition) rather than flat — that pricing engine is explicitly out
     * of scope for this build (§11's chosen answer notes the model, not an
     * implementation). A flat cost per Lead is this phase's documented
     * simplification.
     */
    static final double DEFAULT_LEAD_COST = 10.0;

    private final RequestRepository requestRepository;
    private final ProRepository proRepository;
    private final LeadRepository leadRepository;
    private final EventBus eventBus;

    public MatchingEngine(RequestRepository requestRepository, ProRepository proRepository,
                           LeadRepository leadRepository, EventBus eventBus) {
        this.requestRepository = requestRepository;
        this.proRepository = proRepository;
        this.leadRepository = leadRepository;
        this.eventBus = eventBus;
        eventBus.subscribe(EventTypes.REQUEST_POSTED, this::onRequestPosted);
    }

    private void onRequestPosted(DomainEvent event) {
        String requestId = event.entityId();
        Request request = requestRepository.find(requestId).orElse(null);
        if (request == null) {
            log.warn("REQUEST_POSTED for unknown request {}", requestId);
            return;
        }
        List<ProProfile> matches = proRepository.findMatchingProfiles(
                request.categoryId(), request.locationLat(), request.locationLng());
        for (ProProfile profile : matches) {
            Lead lead = new Lead(
                    UUID.randomUUID().toString(),
                    requestId,
                    profile.proId(),
                    LeadStatus.DELIVERED,
                    DEFAULT_LEAD_COST,
                    Instant.now(),
                    null
            );
            leadRepository.insert(lead);
            eventBus.publish(DomainEvent.of(EventTypes.LEAD_CREATED, lead.leadId(), Map.of("proId", profile.proId())));
        }
        log.info("Request {} matched to {} pro(s)", requestId, matches.size());
    }
}

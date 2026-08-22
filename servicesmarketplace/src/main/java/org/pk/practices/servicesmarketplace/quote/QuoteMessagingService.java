package org.pk.practices.servicesmarketplace.quote;

import org.pk.practices.servicesmarketplace.common.AuthorizationException;
import org.pk.practices.servicesmarketplace.common.DomainException;
import org.pk.practices.servicesmarketplace.eventbus.DomainEvent;
import org.pk.practices.servicesmarketplace.eventbus.EventBus;
import org.pk.practices.servicesmarketplace.eventbus.EventTypes;
import org.pk.practices.servicesmarketplace.lead.Lead;
import org.pk.practices.servicesmarketplace.lead.LeadRepository;
import org.pk.practices.servicesmarketplace.lead.LeadStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DESIGN.md §4.4 — sending a Quote, sending a Message, and (subscribed to
 * {@code REQUEST_HIRED}) the hire fan-out: every other open Quote on the
 * same Request is marked {@code DECLINED} and its Lead {@code LOST}.
 */
public class QuoteMessagingService {

    private static final Logger log = LoggerFactory.getLogger(QuoteMessagingService.class);

    private final QuoteRepository quoteRepository;
    private final MessageRepository messageRepository;
    private final LeadRepository leadRepository;
    private final EventBus eventBus;

    public QuoteMessagingService(QuoteRepository quoteRepository, MessageRepository messageRepository,
                                  LeadRepository leadRepository, EventBus eventBus) {
        this.quoteRepository = quoteRepository;
        this.messageRepository = messageRepository;
        this.leadRepository = leadRepository;
        this.eventBus = eventBus;
        eventBus.subscribe(EventTypes.REQUEST_HIRED, this::onRequestHired);
    }

    /** Requires {@code Lead.status == UNLOCKED} (DESIGN.md §4.4) — a Pro can't quote a Lead they haven't paid to unlock. */
    public Quote sendQuote(String leadId, String proId, double price, String message) {
        Lead lead = leadRepository.find(leadId)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "No lead with id " + leadId));
        if (!lead.proId().equals(proId)) {
            throw new AuthorizationException("This lead was not delivered to you");
        }
        if (lead.status() != LeadStatus.UNLOCKED) {
            throw new DomainException("ILLEGAL_TRANSITION", "Lead " + leadId + " must be UNLOCKED before quoting (was " + lead.status() + ")");
        }
        Quote quote = new Quote(UUID.randomUUID().toString(), leadId, price, message, QuoteStatus.PENDING, Instant.now());
        quoteRepository.insert(quote);
        leadRepository.updateStatus(leadId, LeadStatus.QUOTED);
        eventBus.publish(DomainEvent.of(EventTypes.QUOTE_SENT, quote.quoteId(), Map.of("leadId", leadId, "requestId", lead.requestId())));
        return quote;
    }

    public List<Quote> listForRequest(String requestId) {
        return quoteRepository.findByRequest(requestId);
    }

    public void sendMessage(String requestId, String senderId, String senderType, String body) {
        messageRepository.insert(new Message(UUID.randomUUID().toString(), requestId, senderId, senderType, body, Instant.now()));
    }

    public List<Message> listMessages(String requestId) {
        return messageRepository.findByRequest(requestId);
    }

    @SuppressWarnings("unchecked")
    private void onRequestHired(DomainEvent event) {
        String requestId = event.entityId();
        String winningQuoteId = (String) event.payload().get("quoteId");
        List<Quote> quotesBeforeDecline = quoteRepository.findByRequest(requestId);

        quoteRepository.updateStatus(winningQuoteId, QuoteStatus.ACCEPTED);
        quoteRepository.declineOthersOnSameRequest(winningQuoteId, requestId);

        for (Quote quote : quotesBeforeDecline) {
            if (quote.quoteId().equals(winningQuoteId)) {
                leadRepository.updateStatus(quote.leadId(), LeadStatus.WON);
            } else if (quote.status() == QuoteStatus.PENDING) {
                leadRepository.updateStatus(quote.leadId(), LeadStatus.LOST);
            }
        }
        log.info("Request {} hired via quote {} — {} other quote(s) closed out", requestId, winningQuoteId, quotesBeforeDecline.size() - 1);
    }
}

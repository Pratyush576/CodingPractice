package org.pk.practices.servicesmarketplace.lead;

import org.pk.practices.servicesmarketplace.common.AuthorizationException;
import org.pk.practices.servicesmarketplace.common.DomainException;
import org.pk.practices.servicesmarketplace.credit.CreditLedgerService;

import java.util.List;

/** DESIGN.md §4.2/§4.3 — the Pro-facing half of the Lead lifecycle. Matching/creation lives in {@link org.pk.practices.servicesmarketplace.matching.MatchingEngine}. */
public class LeadService {

    private final LeadRepository leadRepository;
    private final CreditLedgerService creditLedgerService;

    public LeadService(LeadRepository leadRepository, CreditLedgerService creditLedgerService) {
        this.leadRepository = leadRepository;
        this.creditLedgerService = creditLedgerService;
    }

    public List<Lead> listForPro(String proId) {
        return leadRepository.findByPro(proId);
    }

    public Lead get(String leadId, String proId) {
        return requireOwned(leadId, proId);
    }

    /** Idempotent — a second call for the same (leadId, proId) is a no-op, not an error (DESIGN.md §4.3). */
    public Lead unlock(String leadId, String proId) {
        Lead lead = requireOwned(leadId, proId);
        creditLedgerService.unlockLead(leadId, proId, lead.creditCost());
        return leadRepository.find(leadId).orElseThrow();
    }

    private Lead requireOwned(String leadId, String proId) {
        Lead lead = leadRepository.find(leadId)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "No lead with id " + leadId));
        if (!lead.proId().equals(proId)) {
            throw new AuthorizationException("This lead was not delivered to you");
        }
        return lead;
    }
}

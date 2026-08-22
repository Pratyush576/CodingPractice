package org.pk.practices.servicesmarketplace.lead;

import java.util.List;
import java.util.Optional;

public interface LeadRepository {
    void insert(Lead lead);
    Optional<Lead> find(String leadId);
    List<Lead> findByRequest(String requestId);
    List<Lead> findByPro(String proId);

    /** Used for the QUOTED/WON/LOST/EXPIRED transitions — UNLOCKED happens inside {@link org.pk.practices.servicesmarketplace.credit.CreditLedgerRepository#unlockLead} instead, atomically with the deduction. */
    void updateStatus(String leadId, LeadStatus status);
}

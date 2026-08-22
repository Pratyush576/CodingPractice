package org.pk.practices.servicesmarketplace.credit;

/** DESIGN.md §4.3 — thin wrapper over {@link CreditLedgerRepository}; the actual correctness guarantee lives in the repository's single transaction. */
public class CreditLedgerService {

    private final CreditLedgerRepository creditLedgerRepository;

    public CreditLedgerService(CreditLedgerRepository creditLedgerRepository) {
        this.creditLedgerRepository = creditLedgerRepository;
    }

    public void openBalance(String proId) {
        creditLedgerRepository.openBalance(proId);
    }

    public double getBalance(String proId) {
        return creditLedgerRepository.getBalance(proId);
    }

    public void purchaseCredits(String proId, double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        creditLedgerRepository.purchaseCredits(proId, amount);
    }

    /** Throws {@link org.pk.practices.servicesmarketplace.common.ConflictException} on insufficient credits — 409, not a hard failure. */
    public UnlockResult unlockLead(String leadId, String proId, double cost) {
        return creditLedgerRepository.unlockLead(leadId, proId, cost);
    }
}

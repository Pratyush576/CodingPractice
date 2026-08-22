package org.pk.practices.servicesmarketplace.credit;

public interface CreditLedgerRepository {

    /** Called once at Pro registration — a Pro's balance row must exist before any deduction can reference it. */
    void openBalance(String proId);

    double getBalance(String proId);

    /**
     * DESIGN.md §4.3, made real as a single JDBC transaction:
     * <ol>
     *   <li>{@code INSERT INTO lead_unlocks ... ON CONFLICT (lead_id, pro_id) DO NOTHING} —
     *       0 rows affected means this pair was already unlocked; returns {@link UnlockResult#ALREADY_UNLOCKED}
     *       without touching the balance.</li>
     *   <li>Otherwise, {@code UPDATE pro_credit_balances SET balance = balance - ?, version = version + 1
     *       WHERE pro_id = ? AND version = ? AND balance >= ?} — 0 rows affected (insufficient credits, or a
     *       concurrent balance change lost the race) rolls back the whole transaction, undoing step 1's
     *       insert too, and throws {@link org.pk.practices.servicesmarketplace.common.ConflictException}.</li>
     *   <li>Appends a {@code DEDUCTION} row to {@code credit_transactions} (the audit trail).</li>
     * </ol>
     */
    UnlockResult unlockLead(String leadId, String proId, double cost);

    /** A version-CAS retry loop incrementing {@code balance}, plus a {@code PURCHASE} ledger row — no real payment gateway in Phase 1. */
    void purchaseCredits(String proId, double amount);
}

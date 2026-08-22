package org.pk.practices.servicesmarketplace.credit;

import java.time.Instant;

/** DESIGN.md §3 Domain Model — the append-only audit trail. See {@link CreditLedgerService} for the actual balance guarantee. */
public record CreditTransaction(String transactionId, String proId, CreditTransactionType type, double amount, String leadId, Instant createdAt) {}

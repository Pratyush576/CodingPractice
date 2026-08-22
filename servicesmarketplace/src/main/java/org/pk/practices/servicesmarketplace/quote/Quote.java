package org.pk.practices.servicesmarketplace.quote;

import java.time.Instant;

/** DESIGN.md §3 Domain Model. One Quote per Lead — {@code leadId} is unique. */
public record Quote(String quoteId, String leadId, double price, String message, QuoteStatus status, Instant sentAt) {}

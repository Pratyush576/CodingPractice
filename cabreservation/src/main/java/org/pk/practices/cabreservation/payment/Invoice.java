package org.pk.practices.cabreservation.payment;

import java.time.Instant;
import java.util.List;

/**
 * Unused until Phase 2. DESIGN.md §3/§4.7 — the rider-facing, itemized
 * document; {@code total} is what actually gets charged, not a receipt
 * computed after the fact. Immutable once issued.
 */
public record Invoice(String invoiceId, String tripId, String riderId, List<LineItem> lineItems, double total, String status, Instant issuedAt) {}

package org.pk.practices.cabreservation.pricing;

/**
 * DESIGN.md §3/§4.6 — the itemized breakdown §4.7's invoice assembly would
 * pull from. Still unused: {@link PricingStrategy} returns a plain total,
 * not this breakdown, since nothing consumes itemized lines yet (invoicing
 * isn't implemented) — wire this up when it is, rather than build the
 * breakdown ahead of a consumer for it.
 */
public record Fare(String tripId, double estimate, Double finalAmount, double base, double distanceCharge, double timeCharge) {}

package org.pk.practices.cabreservation.pricing;

/** DESIGN.md §3/§4.6 — unused until Phase 2's PricingStrategy lands. */
public record Fare(String tripId, double estimate, Double finalAmount, double base, double distanceCharge, double timeCharge) {}

package org.pk.practices.cabreservation.payment;

/** Unused until Phase 2. One row of an Invoice's itemized breakdown (DESIGN.md §4.7). */
public record LineItem(String lineType, double amount) {}

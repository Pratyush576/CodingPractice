package org.pk.practices.servicesmarketplace.category;

/**
 * DESIGN.md §3/§4.1 — {@code questionnaireSchema} is data, not code, kept
 * here as the raw JSON text. Phase 1 stores it and returns it to clients but
 * doesn't validate {@code Request.answers} against it yet — a documented
 * simplification, not a redesign (real validation is a schema-library
 * addition on top of this same column, not a new column).
 */
public record Category(String categoryId, String name, String questionnaireSchema, String monetizationModel) {}

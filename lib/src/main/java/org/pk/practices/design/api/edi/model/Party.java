package org.pk.practices.design.api.edi.model;

/**
 * Represents a named trading partner in a transaction (buyer, seller, or ship-to).
 *
 * <p>Maps to the X12 {@code N1} segment loop, which identifies parties by role code,
 * name, and an optional ID qualifier/value pair.
 *
 * <h2>Common role codes (N101)</h2>
 * <pre>
 *   BY — Buying Party (buyer)
 *   SE — Selling Party (seller / vendor)
 *   ST — Ship To
 *   BT — Bill To
 * </pre>
 *
 * <h2>Common ID qualifiers (N103)</h2>
 * <pre>
 *   92 — Assigned by buyer or seller
 *   1  — D-U-N-S Number
 *   9  — D-U-N-S+4
 * </pre>
 *
 * @param roleCode    N101 — two-character code identifying the party's role
 * @param name        N102 — full legal or trade name of the party
 * @param idQualifier N103 — code describing what kind of ID {@code id} is
 * @param id          N104 — the actual identifier value
 */
public record Party(String roleCode, String name, String idQualifier, String id) {}

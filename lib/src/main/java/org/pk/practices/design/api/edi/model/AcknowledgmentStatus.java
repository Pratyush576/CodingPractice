package org.pk.practices.design.api.edi.model;

/**
 * The outcome of validating a transaction set, reported in the X12 997
 * Functional Acknowledgment.
 *
 * <p>Maps to the {@code AK5} segment (Transaction Set Response Trailer), element
 * {@code AK501}, and the {@code AK9} segment (Functional Group Response Trailer),
 * element {@code AK901}.
 *
 * <pre>
 *   AK5*A~   → ACCEPTED
 *   AK5*E~   → ACCEPTED_WITH_ERRORS
 *   AK5*R~   → REJECTED
 * </pre>
 */
public enum AcknowledgmentStatus {

    /** Transaction set accepted with no errors. */
    ACCEPTED("A"),

    /** Transaction set accepted, but non-fatal errors were noted. */
    ACCEPTED_WITH_ERRORS("E"),

    /** Transaction set rejected due to errors — the sender must retransmit. */
    REJECTED("R");

    private final String code;

    AcknowledgmentStatus(String code) { this.code = code; }

    /** The single-character X12 code used in the AK5 and AK9 segments. */
    public String code() { return code; }
}

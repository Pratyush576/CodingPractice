package org.pk.practices.design.api.edi.core;

/**
 * The three delimiter characters that govern how an X12 EDI document is tokenised.
 *
 * <h2>X12 delimiter roles</h2>
 * <pre>
 *   ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *260719*1000*^*00501*000000001*0*P*:~
 *      ↑ element separator (*)                                                                          ↑ ↑
 *                                                                             component separator (:) ──┘ │
 *                                                                                  segment terminator (~) ──┘
 * </pre>
 *
 * <ul>
 *   <li><b>element</b> — separates fields within a segment (typically {@code *}).</li>
 *   <li><b>segment</b> — marks the end of a segment (typically {@code ~});
 *       the equivalent of a line ending in CSV.</li>
 *   <li><b>composite</b> — separates sub-fields inside a composite element
 *       (typically {@code :}); rarely used in 850/997 transactions.</li>
 * </ul>
 *
 * <h2>Why delimiters are dynamic</h2>
 * The X12 specification does not mandate fixed delimiter characters. Senders declare
 * their chosen delimiters in the ISA segment so receivers can parse without prior
 * configuration. {@link #fromIsa(String)} extracts them from the raw document.
 *
 * @param element   separates elements within a segment
 * @param segment   terminates a segment
 * @param composite separates components within a composite element
 */
public record EdiDelimiters(char element, char segment, char composite) {

    /** The conventional X12 defaults used by most North American trading partners. */
    public static final EdiDelimiters X12_DEFAULT = new EdiDelimiters('*', '~', ':');

    /**
     * Detects delimiters from the raw ISA segment, which is always exactly 106
     * characters in the X12 standard.
     *
     * <p>Positions are fixed by the spec regardless of the chosen delimiters:
     * <ul>
     *   <li>Position 3  — element separator (the character right after "ISA")</li>
     *   <li>Position 104 — ISA16, the composite element separator</li>
     *   <li>Position 105 — segment terminator (the character that ends the ISA)</li>
     * </ul>
     *
     * @param raw the full EDI document string (only the first 106 chars are examined)
     * @return the delimiters declared in the ISA segment
     * @throws IllegalArgumentException if the input is shorter than 106 characters or
     *                                  does not begin with "ISA"
     */
    public static EdiDelimiters fromIsa(String raw) {
        if (raw == null || raw.length() < 106) {
            throw new IllegalArgumentException(
                    "EDI document must be at least 106 characters (the ISA segment length)");
        }
        if (!raw.startsWith("ISA")) {
            throw new IllegalArgumentException("EDI document must start with 'ISA'");
        }
        return new EdiDelimiters(raw.charAt(3), raw.charAt(105), raw.charAt(104));
    }
}

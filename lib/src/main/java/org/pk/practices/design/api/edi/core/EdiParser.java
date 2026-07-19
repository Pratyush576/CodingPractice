package org.pk.practices.design.api.edi.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses a raw X12 EDI string into a flat list of {@link EdiSegment}s.
 *
 * <h2>Parsing strategy</h2>
 * <ol>
 *   <li><b>Detect delimiters</b> — read them from the fixed-position ISA header
 *       (positions 3, 104, 105). No configuration is needed.</li>
 *   <li><b>Normalise whitespace</b> — EDI files sometimes contain newlines between
 *       segments for human readability. These are stripped before splitting.</li>
 *   <li><b>Split into segments</b> — split on the segment terminator character.
 *       Each raw token becomes one {@link EdiSegment}.</li>
 *   <li><b>Split into elements</b> — within each segment, split on the element
 *       separator. The first token is the segment ID; the rest are elements.</li>
 * </ol>
 *
 * <h2>What this parser does NOT do</h2>
 * <ul>
 *   <li>Validate envelope structure (matching ISA/IEA, GS/GE, ST/SE counts).</li>
 *   <li>Validate control numbers.</li>
 *   <li>Interpret segment semantics — that is the job of the transaction-specific
 *       parsers ({@code PurchaseOrder850Parser}, etc.).</li>
 * </ul>
 *
 * <h2>Two-layer architecture</h2>
 * Separating low-level parsing (this class) from semantic interpretation
 * (the translator layer) means you can write one parser for all transaction sets
 * and many interpreters — one per transaction type.
 */
public class EdiParser {

    /**
     * Parses a raw X12 EDI document string.
     *
     * @param raw the complete EDI document text
     * @return parsed result containing the segment list and the detected delimiters
     * @throws IllegalArgumentException if the document does not start with a valid ISA segment
     */
    public ParseResult parse(String raw) {
        // Normalise: remove newlines that may have been added for readability
        String text = raw.replaceAll("[\\r\\n\\t]", "").trim();

        EdiDelimiters delimiters = EdiDelimiters.fromIsa(text);

        // Split on the segment terminator. Use -1 limit to preserve trailing empty tokens.
        String[] rawSegments = text.split(
                Pattern.quote(String.valueOf(delimiters.segment())), -1);

        List<EdiSegment> segments = new ArrayList<>();
        for (String rawSeg : rawSegments) {
            if (rawSeg.isBlank()) continue;

            // Split on element separator; -1 preserves empty trailing elements
            String[] parts = rawSeg.split(
                    Pattern.quote(String.valueOf(delimiters.element())), -1);

            String segmentId = parts[0].trim();
            if (segmentId.isEmpty()) continue;

            List<String> elements = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                elements.add(parts[i]); // do NOT trim — ISA fields use space-padding
            }
            segments.add(new EdiSegment(segmentId, elements));
        }

        return new ParseResult(segments, delimiters);
    }

    /**
     * The result of a parse operation.
     *
     * @param segments   every segment in the document, in document order
     * @param delimiters the three delimiter characters detected from the ISA segment
     */
    public record ParseResult(List<EdiSegment> segments, EdiDelimiters delimiters) {}
}

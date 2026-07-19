package org.pk.practices.design.api.edi.core;

import java.util.List;

/**
 * Renders a list of {@link EdiSegment}s into a valid X12 EDI string.
 *
 * <h2>Output format</h2>
 * Each segment is written as:
 * <pre>
 *   {ID}{elementSep}{el1}{elementSep}{el2}...{segmentTerm}\n
 * </pre>
 * The optional newline after the segment terminator is a readability aid;
 * the X12 spec does not require it but most EDI tools tolerate it.
 *
 * <h2>Relationship to EdiParser</h2>
 * {@link EdiParser} and {@code EdiWriter} are inverses:
 * {@code parse(write(segments)) ≈ segments} (modulo whitespace normalisation).
 * Together they form the serialisation layer that transaction builders and parsers
 * sit on top of.
 */
public class EdiWriter {

    private final EdiDelimiters delimiters;

    /**
     * @param delimiters the delimiter set to use when rendering segments
     */
    public EdiWriter(EdiDelimiters delimiters) {
        this.delimiters = delimiters;
    }

    /**
     * Renders all segments to a single EDI string.
     *
     * <p>The ISA segment, which must be the first element in the list, is rendered with
     * its fixed-width fields intact — the caller is responsible for ensuring that ISA06
     * and ISA08 (sender/receiver IDs) are exactly 15 characters each.
     *
     * @param segments the ordered list of segments to render
     * @return a complete, ready-to-transmit EDI document string
     */
    public String write(List<EdiSegment> segments) {
        StringBuilder sb = new StringBuilder();
        for (EdiSegment seg : segments) {
            sb.append(seg.toText(delimiters.element()));
            sb.append(delimiters.segment());
            sb.append('\n'); // readability newline — safe for all standard parsers
        }
        return sb.toString();
    }
}

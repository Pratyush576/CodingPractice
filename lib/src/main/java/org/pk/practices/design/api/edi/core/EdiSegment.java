package org.pk.practices.design.api.edi.core;

import java.util.List;

/**
 * A single X12 EDI segment — the fundamental unit of an EDI document.
 *
 * <h2>Anatomy of a segment</h2>
 * <pre>
 *   PO1*1*10*EA*9.99**UP*00012345678905~
 *    ↑   ↑  ↑  ↑  ↑  ↑ ↑  ↑
 *   ID  01 02 03 04 05 06  07   ← element indices (1-based per X12 convention)
 * </pre>
 *
 * <ul>
 *   <li>The first token before the first element separator is the <b>segment ID</b>
 *       (e.g. {@code PO1}, {@code ISA}, {@code N1}).</li>
 *   <li>Subsequent tokens are <b>elements</b>, numbered from 1.</li>
 *   <li>An empty element (two adjacent separators) represents an omitted optional
 *       field; {@link #element(int)} returns an empty string in that case.</li>
 * </ul>
 *
 * <h2>1-based indexing</h2>
 * X12 documentation refers to elements as {@code PO101}, {@code PO102}, etc.
 * {@link #element(int)} uses the same 1-based convention so code maps naturally
 * to spec references.
 */
public final class EdiSegment {

    private final String id;
    private final List<String> elements;

    /**
     * Constructs a segment from a pre-split list.
     *
     * @param id       the segment identifier (e.g. {@code "BEG"})
     * @param elements the element values in order (must not be null)
     */
    public EdiSegment(String id, List<String> elements) {
        this.id       = id;
        this.elements = List.copyOf(elements);
    }

    /** Returns the segment identifier (e.g. {@code "PO1"}, {@code "ISA"}). */
    public String id() { return id; }

    /**
     * Returns the value of the element at the given 1-based index.
     *
     * <p>Returns an empty string if {@code index} is beyond the number of elements
     * in this segment — callers can safely call this without bounds checks.
     *
     * @param index the 1-based element position (ISA01, BEG01, PO101, …)
     * @return the element value, or {@code ""} if absent
     */
    public String element(int index) {
        int i = index - 1;
        return (i >= 0 && i < elements.size()) ? elements.get(i) : "";
    }

    /** Returns all element values in order (immutable). */
    public List<String> elements() { return elements; }

    /** Returns the number of elements in this segment (not counting the segment ID). */
    public int elementCount() { return elements.size(); }

    /**
     * Reconstructs the segment text using the provided delimiter (without the trailing
     * segment terminator). Useful for logging and debugging.
     */
    public String toText(char elementSeparator) {
        if (elements.isEmpty()) return id;
        return id + elementSeparator + String.join(String.valueOf(elementSeparator), elements);
    }

    @Override
    public String toString() { return toText('*'); }
}

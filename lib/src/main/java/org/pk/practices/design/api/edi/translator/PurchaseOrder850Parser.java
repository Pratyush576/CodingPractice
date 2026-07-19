package org.pk.practices.design.api.edi.translator;

import org.pk.practices.design.api.edi.core.EdiSegment;
import org.pk.practices.design.api.edi.model.Party;
import org.pk.practices.design.api.edi.model.PurchaseOrder;
import org.pk.practices.design.api.edi.model.PurchaseOrderLine;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Translates a flat list of X12 segments into a typed {@link PurchaseOrder} object.
 *
 * <h2>Translation layer responsibility</h2>
 * {@link org.pk.practices.design.api.edi.core.EdiParser} produces a generic
 * {@code List<EdiSegment>} — it understands nothing about transaction types.
 * This class understands the 850 spec: which segment carries which business data,
 * how to handle optional segments, and how to correlate multi-segment constructs
 * (PO1 + PID belong to the same line item).
 *
 * <h2>Segment coverage</h2>
 * <pre>
 *   BEG — purchase order number, purpose, date
 *   CUR — currency code
 *   DTM — dates (qualifier 002 = delivery requested)
 *   N1  — trading partner (BY=buyer, SE=seller)
 *   PO1 — line item (number, qty, UOM, price, product ID)
 *   PID — line description (free-form text for the preceding PO1)
 *   CTT — transaction totals (parsed but not stored — derivable from lines)
 * </pre>
 *
 * <h2>PO1 + PID correlation</h2>
 * The PID segment that follows a PO1 segment belongs to that line. Because
 * {@link PurchaseOrderLine} is an immutable record, we temporarily hold a mutable
 * pending-line state and flush it (into an immutable record) when the next PO1 or
 * CTT segment arrives.
 */
public class PurchaseOrder850Parser {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Parses an 850 transaction from the provided segment list.
     *
     * <p>The list may contain the full interchange (ISA through IEA) or just the
     * transaction-set segments (ST through SE). Unknown segments are silently ignored,
     * making the parser forward-compatible with optional segments it does not model.
     *
     * @param segments all segments in document order
     * @return the populated {@link PurchaseOrder} domain object
     * @throws IllegalStateException if required BEG segment is absent
     */
    public PurchaseOrder parse(List<EdiSegment> segments) {
        String    poNumber    = null;
        String    purpose     = "00";
        LocalDate poDate      = null;
        LocalDate deliveryDate = null;
        String    currency    = "USD";
        Party     buyer       = null;
        Party     seller      = null;

        List<PurchaseOrderLine> lines = new ArrayList<>();

        // Pending state for the line currently being assembled from PO1 + optional PID
        int    pendingLineNum  = 0;
        double pendingQty      = 0;
        String pendingUom      = "";
        double pendingPrice    = 0;
        String pendingCodeQual = "";
        String pendingCode     = "";
        String pendingDesc     = "";
        boolean hasPending     = false;

        for (EdiSegment seg : segments) {
            switch (seg.id()) {

                case "BEG" -> {
                    purpose  = seg.element(1);
                    poNumber = seg.element(3);
                    poDate   = parseDate(seg.element(5));
                }

                case "CUR" -> currency = seg.element(2);

                case "DTM" -> {
                    // Qualifier "002" = Delivery Requested
                    if ("002".equals(seg.element(1))) {
                        deliveryDate = parseDate(seg.element(2));
                    }
                }

                case "N1" -> {
                    Party party = new Party(
                            seg.element(1), // role: BY or SE
                            seg.element(2), // name
                            seg.element(3), // id qualifier
                            seg.element(4)  // id value
                    );
                    if ("BY".equals(seg.element(1)))      buyer  = party;
                    else if ("SE".equals(seg.element(1))) seller = party;
                }

                case "PO1" -> {
                    // Flush the previous pending line before starting a new one
                    if (hasPending) {
                        lines.add(buildLine(pendingLineNum, pendingQty, pendingUom,
                                pendingPrice, pendingCodeQual, pendingCode, pendingDesc));
                    }
                    pendingLineNum  = parseInt(seg.element(1));
                    pendingQty      = parseDouble(seg.element(2));
                    pendingUom      = seg.element(3);
                    pendingPrice    = parseDouble(seg.element(4));
                    // PO106/PO107: product code qualifier and value
                    pendingCodeQual = seg.element(6);
                    pendingCode     = seg.element(7);
                    pendingDesc     = "";
                    hasPending      = true;
                }

                case "PID" -> {
                    // PID05 = free-form item description for the current PO1 line
                    if (hasPending) pendingDesc = seg.element(5);
                }

                case "CTT" -> {
                    // Flush the last pending line; CTT marks end of line items
                    if (hasPending) {
                        lines.add(buildLine(pendingLineNum, pendingQty, pendingUom,
                                pendingPrice, pendingCodeQual, pendingCode, pendingDesc));
                        hasPending = false;
                    }
                }

                default -> { /* forward-compatible: ignore unrecognised segments */ }
            }
        }

        // Handle case where CTT is absent (non-standard but tolerated)
        if (hasPending) {
            lines.add(buildLine(pendingLineNum, pendingQty, pendingUom,
                    pendingPrice, pendingCodeQual, pendingCode, pendingDesc));
        }

        if (poNumber == null) {
            throw new IllegalStateException("Required BEG segment (purchase order number) not found");
        }

        return new PurchaseOrder(poNumber, purpose, poDate, deliveryDate, currency, buyer, seller, lines);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private PurchaseOrderLine buildLine(int num, double qty, String uom, double price,
                                        String codeQual, String code, String desc) {
        return new PurchaseOrderLine(num, qty, uom, price, codeQual, code, desc);
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim(), DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }
}

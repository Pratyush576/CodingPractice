package org.pk.practices.design.api.edi.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Domain model for an X12 850 Purchase Order transaction.
 *
 * <h2>X12 850 high-level structure</h2>
 * <pre>
 *   ISA  ─── Interchange envelope (outermost)
 *   GS   ─── Functional group (groups related transactions)
 *   ST   ─── Transaction set header  ┐
 *   BEG  ─── PO details              │
 *   CUR  ─── Currency                │ 850 transaction set
 *   DTM  ─── Date references         │
 *   N1   ─── Trading partner names   │
 *   PO1  ─── Line items (per line)   │
 *   CTT  ─── Transaction totals      │
 *   SE   ─── Transaction set trailer ┘
 *   GE   ─── Functional group trailer
 *   IEA  ─── Interchange trailer
 * </pre>
 *
 * @param poNumber             BEG03 — the buyer's purchase order number
 * @param purposeCode          BEG01 — "00"=original, "05"=replace, "06"=cancel
 * @param poDate               BEG05 — date the PO was issued (YYYYMMDD in EDI)
 * @param requestedDeliveryDate DTM02 with qualifier "002" — requested ship/delivery date
 * @param currency             CUR02 — ISO 4217 code (e.g. "USD")
 * @param buyer                N1 loop with role "BY"
 * @param seller               N1 loop with role "SE"
 * @param lines                PO1 line items
 */
public record PurchaseOrder(
        String              poNumber,
        String              purposeCode,
        LocalDate           poDate,
        LocalDate           requestedDeliveryDate,
        String              currency,
        Party               buyer,
        Party               seller,
        List<PurchaseOrderLine> lines
) {
    /** Sum of all {@link PurchaseOrderLine#lineTotal()} values. */
    public double grandTotal() {
        return lines.stream().mapToDouble(PurchaseOrderLine::lineTotal).sum();
    }

    /** Total quantity across all lines (CTT02 in the EDI). */
    public double totalQuantity() {
        return lines.stream().mapToDouble(PurchaseOrderLine::quantity).sum();
    }
}

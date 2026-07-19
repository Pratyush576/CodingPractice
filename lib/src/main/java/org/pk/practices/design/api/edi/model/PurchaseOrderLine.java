package org.pk.practices.design.api.edi.model;

/**
 * A single line item within a Purchase Order (X12 850).
 *
 * <p>Maps primarily to the {@code PO1} segment with an optional {@code PID}
 * segment immediately following for the human-readable description.
 *
 * <h2>X12 segment mapping</h2>
 * <pre>
 *   PO1*1*10*EA*9.99**UP*00012345678905~
 *       ↑  ↑  ↑  ↑      ↑  ↑
 *      01 02 03 04      06  07
 *
 *   PO101 — line number (sequential, starting at 1)
 *   PO102 — quantity ordered
 *   PO103 — unit of measure (EA=each, CS=case, LB=pound, DZ=dozen)
 *   PO104 — unit price
 *   PO105 — basis of unit price (blank = per unit)
 *   PO106 — product/service ID qualifier (UP=UPC, SK=seller style)
 *   PO107 — product/service ID value (e.g. the UPC barcode)
 *
 *   PID*F****Blue Widget~
 *        ↑    ↑↑↑↑  ↑
 *       01   02-04  05
 *   PID01 — item description type (F=free-form)
 *   PID05 — item description text
 * </pre>
 *
 * @param lineNumber           PO101 — 1-based line sequence number
 * @param quantity             PO102 — ordered quantity
 * @param unitOfMeasure        PO103 — unit code (EA, CS, LB, …)
 * @param unitPrice            PO104 — price per unit
 * @param productCodeQualifier PO106 — qualifier for {@code productCode}
 * @param productCode          PO107 — product identifier (UPC, SKU, …)
 * @param description          PID05 — free-form product description (may be empty)
 */
public record PurchaseOrderLine(
        int    lineNumber,
        double quantity,
        String unitOfMeasure,
        double unitPrice,
        String productCodeQualifier,
        String productCode,
        String description
) {
    /** Returns the extended price for this line (quantity × unitPrice). */
    public double lineTotal() { return quantity * unitPrice; }
}

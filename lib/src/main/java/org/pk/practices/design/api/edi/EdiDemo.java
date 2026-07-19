package org.pk.practices.design.api.edi;

import org.pk.practices.design.api.edi.core.EdiDelimiters;
import org.pk.practices.design.api.edi.core.EdiParser;
import org.pk.practices.design.api.edi.core.EdiSegment;
import org.pk.practices.design.api.edi.core.EdiWriter;
import org.pk.practices.design.api.edi.model.AcknowledgmentStatus;
import org.pk.practices.design.api.edi.model.Party;
import org.pk.practices.design.api.edi.model.PurchaseOrder;
import org.pk.practices.design.api.edi.model.PurchaseOrderLine;
import org.pk.practices.design.api.edi.translator.Acknowledgment997Builder;
import org.pk.practices.design.api.edi.translator.PurchaseOrder850Builder;
import org.pk.practices.design.api.edi.translator.PurchaseOrder850Parser;

import java.time.LocalDate;
import java.util.List;

/**
 * End-to-end demonstration of the EDI implementation.
 *
 * <h2>What this demo shows</h2>
 * <ol>
 *   <li><b>Build</b> — construct a {@link PurchaseOrder} domain object in code.</li>
 *   <li><b>Generate</b> — use {@link PurchaseOrder850Builder} to produce a valid X12 850
 *       EDI string (ISA → GS → ST/BEG/PO1s/CTT/SE → GE → IEA).</li>
 *   <li><b>Parse</b> — feed that string into {@link EdiParser} to get a flat segment
 *       list, then into {@link PurchaseOrder850Parser} to recover the domain object.</li>
 *   <li><b>Acknowledge</b> — use {@link Acknowledgment997Builder} to generate the 997
 *       Functional Acknowledgment the receiver must send back.</li>
 * </ol>
 *
 * <h2>Real-world flow</h2>
 * In production, steps 2 and 3 happen in different systems:
 * <pre>
 *   Buyer system  ──[generate 850]──► EDI VAN/AS2 ──► Seller system
 *   Buyer system  ◄──[997 ACK]──────  EDI VAN/AS2 ◄── Seller system
 * </pre>
 * The VAN (Value Added Network) or AS2 protocol handles the secure file transfer;
 * this demo skips that transport layer.
 */
public class EdiDemo {

    public static void main(String[] args) {

        // ── Step 1: Build a Purchase Order domain object ──────────────────────────
        PurchaseOrder po = new PurchaseOrder(
                "PO-2026-00123",
                "00",                          // purpose: original
                LocalDate.of(2026, 7, 19),
                LocalDate.of(2026, 7, 26),     // requested delivery
                "USD",
                new Party("BY", "ACME Corp",   "92", "BUYER-001"),
                new Party("SE", "Widget LLC",  "92", "VENDOR-001"),
                List.of(
                        new PurchaseOrderLine(1, 10, "EA",  9.99, "UP", "00012345678905", "Blue Widget"),
                        new PurchaseOrderLine(2,  5, "EA", 24.99, "UP", "00098765432109", "Premium Gadget"),
                        new PurchaseOrderLine(3, 20, "CS",  4.50, "UP", "00055512340001", "Value Pack")
                )
        );

        // ── Step 2: Generate the 850 EDI string ──────────────────────────────────
        EdiDelimiters delimiters = EdiDelimiters.X12_DEFAULT;
        List<EdiSegment> segments850 = new PurchaseOrder850Builder(delimiters)
                .build(po, "ACME-CORP", "WIDGET-LLC", 1);
        String edi850 = new EdiWriter(delimiters).write(segments850);

        printSection("GENERATED X12 850 — Purchase Order", edi850);

        // ── Step 3: Parse the 850 back into a domain object ──────────────────────
        EdiParser.ParseResult parsed = new EdiParser().parse(edi850);
        PurchaseOrder parsedPo = new PurchaseOrder850Parser().parse(parsed.segments());

        printSection("PARSED 850 — Domain Object", formatPo(parsedPo));

        // ── Step 4: Generate the 997 Functional Acknowledgment ───────────────────
        // The receiver sends this back to confirm the 850 was received and valid.
        // Note: sender/receiver are swapped (receiver of 850 is sender of 997).
        List<EdiSegment> segments997 = new Acknowledgment997Builder(delimiters)
                .build("WIDGET-LLC", "ACME-CORP",
                       "1",      // original GS06 (group control number)
                       "0001",   // original ST02 (transaction set control number)
                       AcknowledgmentStatus.ACCEPTED, 2);
        String edi997 = new EdiWriter(delimiters).write(segments997);

        printSection("GENERATED X12 997 — Functional Acknowledgment", edi997);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String formatPo(PurchaseOrder po) {
        StringBuilder sb = new StringBuilder();
        sb.append("  PO Number   : ").append(po.poNumber()).append('\n');
        sb.append("  Purpose     : ").append(po.purposeCode()).append('\n');
        sb.append("  PO Date     : ").append(po.poDate()).append('\n');
        sb.append("  Delivery    : ").append(po.requestedDeliveryDate()).append('\n');
        sb.append("  Currency    : ").append(po.currency()).append('\n');
        if (po.buyer()  != null) sb.append("  Buyer       : ").append(po.buyer().name()).append(" (").append(po.buyer().id()).append(")\n");
        if (po.seller() != null) sb.append("  Seller      : ").append(po.seller().name()).append(" (").append(po.seller().id()).append(")\n");
        sb.append("  Lines       :\n");
        for (PurchaseOrderLine line : po.lines()) {
            sb.append(String.format("    #%d  qty=%-4s  UOM=%-2s  price=$%-8.2f  [%s:%s]  %s  → $%.2f%n",
                    line.lineNumber(), formatNum(line.quantity()), line.unitOfMeasure(),
                    line.unitPrice(), line.productCodeQualifier(), line.productCode(),
                    line.description(), line.lineTotal()));
        }
        sb.append(String.format("  Grand Total : $%.2f%n", po.grandTotal()));
        return sb.toString();
    }

    private static String formatNum(double d) {
        return (d == Math.floor(d)) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static void printSection(String title, String body) {
        String line = "─".repeat(70);
        System.out.println("\n" + line);
        System.out.println("  " + title);
        System.out.println(line);
        System.out.println(body);
    }
}

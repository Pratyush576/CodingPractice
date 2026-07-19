package org.pk.practices.design.api.edi.translator;

import org.pk.practices.design.api.edi.core.EdiDelimiters;
import org.pk.practices.design.api.edi.core.EdiSegment;
import org.pk.practices.design.api.edi.model.Party;
import org.pk.practices.design.api.edi.model.PurchaseOrder;
import org.pk.practices.design.api.edi.model.PurchaseOrderLine;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a complete X12 850 EDI document from a {@link PurchaseOrder} domain object.
 *
 * <h2>Document structure produced</h2>
 * <pre>
 *   ISA  — Interchange Control Header  (outermost envelope)
 *   GS   — Functional Group Header
 *     ST   — Transaction Set Header    ┐
 *     BEG  — Beginning Segment         │
 *     CUR  — Currency                  │
 *     DTM  — Delivery Date (if set)    │  850 Transaction Set
 *     N1   — Buyer                     │
 *     N1   — Seller                    │
 *     PO1  — Line 1                    │
 *     PID  — Line 1 Description        │
 *     ...                              │
 *     CTT  — Transaction Totals        │
 *     SE   — Transaction Set Trailer   ┘
 *   GE   — Functional Group Trailer
 *   IEA  — Interchange Control Trailer
 * </pre>
 *
 * <h2>SE01 (segment count) calculation</h2>
 * SE01 must equal the number of segments from ST through SE inclusive. This builder
 * collects all transaction-set segments into a temporary list, counts them, then
 * appends the SE with the correct count before adding the GE/IEA envelope closers.
 *
 * <h2>ISA fixed-length fields</h2>
 * ISA06 (sender ID) and ISA08 (receiver ID) must be exactly 15 characters, right-padded
 * with spaces. {@link #pad(String, int)} handles this. The composite element separator
 * (ISA16) is a literal colon ({@code :}) because the 850/997 transaction sets do not
 * use composite elements.
 */
public class PurchaseOrder850Builder {

    private static final DateTimeFormatter DATE_6  = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter DATE_8  = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_4  = DateTimeFormatter.ofPattern("HHmm");

    private final EdiDelimiters delimiters;

    public PurchaseOrder850Builder(EdiDelimiters delimiters) {
        this.delimiters = delimiters;
    }

    /**
     * Generates all EDI segments for the given purchase order.
     *
     * @param po            the purchase order to encode
     * @param senderId      the sender's EDI ID (padded to 15 chars by this method)
     * @param receiverId    the receiver's EDI ID (padded to 15 chars)
     * @param controlNumber a unique 9-digit interchange control number (ISA13 / IEA02)
     * @return ordered list of all segments from ISA to IEA
     */
    public List<EdiSegment> build(PurchaseOrder po, String senderId,
                                  String receiverId, int controlNumber) {
        String now        = LocalTime.now().format(TIME_4);
        String today6     = LocalDate.now().format(DATE_6);
        String today8     = LocalDate.now().format(DATE_8);
        String ctrlStr    = String.format("%09d", controlNumber);
        String poDateStr  = po.poDate() != null ? po.poDate().format(DATE_8) : today8;

        List<EdiSegment> all = new ArrayList<>();

        // ── Interchange envelope ───────────────────────────────────────────
        all.add(seg("ISA",
                "00", pad("", 10),        // ISA01/02 — no authorisation
                "00", pad("", 10),        // ISA03/04 — no security
                "ZZ", pad(senderId, 15),  // ISA05/06 — sender qualifier + ID
                "ZZ", pad(receiverId, 15),// ISA07/08 — receiver qualifier + ID
                today6, now,              // ISA09/10 — date + time
                "^",                      // ISA11 — repetition separator
                "00501",                  // ISA12 — version
                ctrlStr,                  // ISA13 — control number
                "0",                      // ISA14 — no ack requested
                "P",                      // ISA15 — P=production, T=test
                ":"                       // ISA16 — component element separator
        ));

        // ── Functional group ──────────────────────────────────────────────
        all.add(seg("GS",
                "PO",          // GS01 — functional identifier (PO = purchase orders)
                senderId,      // GS02 — application sender code
                receiverId,    // GS03 — application receiver code
                today8,        // GS04 — date (YYYYMMDD)
                now,           // GS05 — time
                "1",           // GS06 — group control number
                "X",           // GS07 — responsible agency (X = ANSI ASC X12)
                "005010"       // GS08 — version/release
        ));

        // ── Transaction set (ST → SE) ─────────────────────────────────────
        // Collect in a separate list so we can count them for SE01.
        List<EdiSegment> ts = new ArrayList<>();

        ts.add(seg("ST", "850", "0001"));  // ST01=transaction type, ST02=control number

        ts.add(seg("BEG",
                po.purposeCode(),  // BEG01 — purpose (00=original)
                "NE",              // BEG02 — order type (NE=new order)
                po.poNumber(),     // BEG03 — PO number
                "",                // BEG04 — release number (blank)
                poDateStr          // BEG05 — PO date YYYYMMDD
        ));

        if (po.currency() != null && !po.currency().isBlank()) {
            ts.add(seg("CUR", "BY", po.currency())); // CUR01=qualifier, CUR02=ISO code
        }

        if (po.requestedDeliveryDate() != null) {
            ts.add(seg("DTM",
                    "002",                                      // qualifier: Delivery Requested
                    po.requestedDeliveryDate().format(DATE_8)   // YYYYMMDD
            ));
        }

        addParty(ts, po.buyer());
        addParty(ts, po.seller());

        for (PurchaseOrderLine line : po.lines()) {
            ts.add(seg("PO1",
                    String.valueOf(line.lineNumber()),    // PO101 — line number
                    formatNumber(line.quantity()),         // PO102 — quantity
                    line.unitOfMeasure(),                  // PO103 — UOM
                    formatPrice(line.unitPrice()),         // PO104 — unit price
                    "",                                    // PO105 — basis of price (blank)
                    line.productCodeQualifier(),           // PO106 — product ID qualifier
                    line.productCode()                     // PO107 — product ID
            ));
            if (line.description() != null && !line.description().isBlank()) {
                ts.add(seg("PID",
                        "F",                  // PID01 — F=free-form description
                        "", "", "",           // PID02–04 — not used
                        line.description()   // PID05 — description text
                ));
            }
        }

        ts.add(seg("CTT",
                String.valueOf(po.lines().size()),   // CTT01 — number of line items
                formatNumber(po.totalQuantity())      // CTT02 — total quantity across all lines
        ));

        // SE01 = segment count from ST through SE inclusive (ts.size() + 1 for SE)
        ts.add(seg("SE", String.valueOf(ts.size() + 1), "0001"));

        all.addAll(ts);

        // ── Close envelope ────────────────────────────────────────────────
        all.add(seg("GE", "1", "1"));          // GE01=transaction count, GE02=group control
        all.add(seg("IEA", "1", ctrlStr));     // IEA01=group count, IEA02=control number

        return all;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void addParty(List<EdiSegment> ts, Party party) {
        if (party == null) return;
        ts.add(seg("N1",
                party.roleCode(),    // N101 — entity role
                party.name(),        // N102 — name
                party.idQualifier(), // N103 — ID qualifier
                party.id()           // N104 — ID value
        ));
    }

    /** Creates a segment from varargs element strings. */
    private EdiSegment seg(String id, String... elements) {
        return new EdiSegment(id, List.of(elements));
    }

    /** Right-pads or truncates a string to exactly {@code length} characters. */
    private String pad(String s, int length) {
        return String.format("%-" + length + "s", s == null ? "" : s);
    }

    /** Formats a quantity as an integer when it has no fractional part, otherwise as decimal. */
    private String formatNumber(double d) {
        return (d == Math.floor(d)) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** Formats a price with exactly two decimal places. */
    private String formatPrice(double price) {
        return String.format("%.2f", price);
    }
}

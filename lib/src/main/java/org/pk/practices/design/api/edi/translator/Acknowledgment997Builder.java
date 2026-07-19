package org.pk.practices.design.api.edi.translator;

import org.pk.practices.design.api.edi.core.EdiDelimiters;
import org.pk.practices.design.api.edi.core.EdiSegment;
import org.pk.practices.design.api.edi.model.AcknowledgmentStatus;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates an X12 997 Functional Acknowledgment in response to a received 850
 * (or any other functional group).
 *
 * <h2>What is a 997?</h2>
 * Every X12 trading partner that receives a functional group <em>must</em> respond
 * with a 997 acknowledging receipt. It is the EDI equivalent of an HTTP response
 * status — without it the sender cannot know whether their document was received
 * and structurally valid.
 *
 * <h2>997 segment structure</h2>
 * <pre>
 *   ISA  — Interchange envelope (sender/receiver IDs are swapped from the 850)
 *   GS   — Functional group (GS01 = "FA" for Functional Acknowledgment)
 *     ST*997              — transaction set header
 *     AK1*PO*{GS06}       — identifies the 850 functional group being acknowledged
 *     AK2*850*{ST02}      — identifies the specific transaction set
 *     AK5*{status}        — transaction set response (A/E/R)
 *     AK9*{status}*1*1*1  — functional group response
 *     SE*6*{ST02}         — segment count (6 segments: ST+AK1+AK2+AK5+AK9+SE)
 *   GE   — Functional group trailer
 *   IEA  — Interchange trailer
 * </pre>
 *
 * <h2>Sender/receiver reversal</h2>
 * The ISA06/ISA08 fields are <em>swapped</em> relative to the original 850: the
 * 850 receiver (this system) becomes the 997 sender, and the 850 sender becomes
 * the 997 receiver.
 */
public class Acknowledgment997Builder {

    private static final DateTimeFormatter DATE_6 = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter DATE_8 = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_4 = DateTimeFormatter.ofPattern("HHmm");

    private final EdiDelimiters delimiters;

    public Acknowledgment997Builder(EdiDelimiters delimiters) {
        this.delimiters = delimiters;
    }

    /**
     * Builds a complete 997 acknowledgment.
     *
     * @param ackSenderId      this system's EDI ID (was the receiver in the original 850)
     * @param ackReceiverId    the trading partner's EDI ID (was the sender in the 850)
     * @param originalGroupControlNumber ISA13 / GS06 from the 850 being acknowledged
     * @param originalTsControlNumber    ST02 from the 850 transaction set being acknowledged
     * @param status           the outcome of structural validation
     * @param controlNumber    a unique control number for this 997 interchange
     * @return ordered list of all segments from ISA to IEA
     */
    public List<EdiSegment> build(
            String ackSenderId,
            String ackReceiverId,
            String originalGroupControlNumber,
            String originalTsControlNumber,
            AcknowledgmentStatus status,
            int controlNumber
    ) {
        String now     = LocalTime.now().format(TIME_4);
        String today6  = LocalDate.now().format(DATE_6);
        String today8  = LocalDate.now().format(DATE_8);
        String ctrlStr = String.format("%09d", controlNumber);

        List<EdiSegment> all = new ArrayList<>();

        // ISA — note sender/receiver are REVERSED relative to the inbound 850
        all.add(seg("ISA",
                "00", pad("", 10),
                "00", pad("", 10),
                "ZZ", pad(ackSenderId, 15),
                "ZZ", pad(ackReceiverId, 15),
                today6, now,
                "^", "00501", ctrlStr, "0", "P", ":"
        ));

        // GS — GS01 = "FA" identifies this as a Functional Acknowledgment group
        all.add(seg("GS", "FA", ackSenderId, ackReceiverId, today8, now, "1", "X", "005010"));

        // Transaction set
        List<EdiSegment> ts = new ArrayList<>();

        ts.add(seg("ST", "997", "0001"));

        // AK1 — identifies the functional group being acknowledged
        // AK101 = "PO" because the 850 group uses GS01 = "PO"
        ts.add(seg("AK1", "PO", originalGroupControlNumber));

        // AK2 — identifies the specific transaction set (the 850)
        ts.add(seg("AK2", "850", originalTsControlNumber));

        // AK5 — transaction set response
        // AK501 = A (accepted), E (accepted with errors), R (rejected)
        ts.add(seg("AK5", status.code()));

        // AK9 — functional group response
        // AK901 = status code
        // AK902 = number of transaction sets included in the group (1)
        // AK903 = number of received transaction sets (1)
        // AK904 = number of accepted transaction sets
        String accepted = (status == AcknowledgmentStatus.REJECTED) ? "0" : "1";
        ts.add(seg("AK9", status.code(), "1", "1", accepted));

        // SE01 = segment count ST through SE inclusive = ts.size() + 1
        ts.add(seg("SE", String.valueOf(ts.size() + 1), "0001"));

        all.addAll(ts);

        all.add(seg("GE", "1", "1"));
        all.add(seg("IEA", "1", ctrlStr));

        return all;
    }

    private EdiSegment seg(String id, String... elements) {
        return new EdiSegment(id, List.of(elements));
    }

    private String pad(String s, int length) {
        return String.format("%-" + length + "s", s == null ? "" : s);
    }
}

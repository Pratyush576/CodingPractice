package org.pk.practices.supplychain.booking;

/** DESIGN.md §4.1 Incoterm responsibility matrix. FAS/FOB/CFR/CIF are ocean-only. */
public enum Incoterm {
    EXW, FCA, CPT, CIP, DAP, DPU, DDP,
    FAS, FOB, CFR, CIF;

    public boolean isOceanOnly() {
        return this == FAS || this == FOB || this == CFR || this == CIF;
    }
}

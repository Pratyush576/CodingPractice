package org.pk.practices.supplychain.booking;

import java.math.BigDecimal;

/** DESIGN.md §3 — one commodity line within a Booking. */
public record CargoLineItem(
        String lineId,
        String hsCode,
        String description,
        String countryOfOrigin,
        BigDecimal quantity,
        String unitOfMeasure,
        BigDecimal lineWeightKg,
        BigDecimal lineValueAmount,
        String lineValueCurrency,
        String dgClass,
        String unNumber,
        String packingGroup
) {}

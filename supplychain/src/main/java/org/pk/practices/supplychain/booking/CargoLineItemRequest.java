package org.pk.practices.supplychain.booking;

import java.math.BigDecimal;

/** Wire shape for a cargo line item — no lineId yet, no enums yet; validated before becoming a {@link CargoLineItem}. */
public record CargoLineItemRequest(
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

package org.pk.practices.supplychain.matching;

import java.math.BigDecimal;
import java.util.List;

/**
 * Wire shape for {@code POST /v1/capacity-offerings}. Like
 * {@code CreateBookingRequest}, dates are raw ISO-8601 strings so a bad one
 * becomes a collected validation violation, not a deserialization crash.
 */
public record CreateCapacityOfferingRequest(
        String mode,
        String originNodeId,
        String destinationNodeId,
        List<ContainerCapacityRequest> containerCapacities,
        BigDecimal totalWeightKg,
        BigDecimal totalVolumeCbm,
        BigDecimal rateAmount,
        String rateCurrency,
        String validFrom,
        String validUntil
) {}

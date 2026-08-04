package org.pk.practices.supplychain.matching;

import org.pk.practices.supplychain.booking.Booking;
import org.pk.practices.supplychain.booking.ContainerRequirement;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a Booking needs, translated into the shape Matching checks against —
 * mirrors the FCL-vs-LCL/Breakbulk exclusivity already enforced when a
 * Booking is created: {@code containerRequirements} non-empty means FCL,
 * otherwise weight/volume apply.
 */
public record CapacityRequirement(
        List<ContainerRequirement> containerRequirements,
        BigDecimal totalWeightKg,
        BigDecimal totalVolumeCbm
) {
    /** Single source of truth — both findCandidates() and BookingService.reserveCapacity() need the exact same translation. */
    public static CapacityRequirement from(Booking booking) {
        return new CapacityRequirement(booking.containerRequirements(), booking.totalWeightKg(), booking.totalVolumeCbm());
    }
}

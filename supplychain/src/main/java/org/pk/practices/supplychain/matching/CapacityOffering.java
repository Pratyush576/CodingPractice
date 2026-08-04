package org.pk.practices.supplychain.matching;

import org.pk.practices.supplychain.booking.ContainerRequirement;
import org.pk.practices.supplychain.booking.TransportMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * LLD.md §4 Matching Engine's {@code hasCapacityFor()}/{@code decrement()} —
 * pure functions, immutable, so {@code reserve()}'s CAS loop can read, check,
 * and build the next version without any shared mutable state.
 */
public record CapacityOffering(
        String tenantId,
        String offeringId,
        String operatorId,
        TransportMode mode,
        String originNodeId,
        String destinationNodeId,
        List<ContainerCapacity> containerCapacities,
        BigDecimal totalWeightKg,
        BigDecimal availableWeightKg,
        BigDecimal totalVolumeCbm,
        BigDecimal availableVolumeCbm,
        BigDecimal rateAmount,
        String rateCurrency,
        Instant validFrom,
        Instant validUntil,
        CapacityOfferingStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public boolean hasCapacityFor(CapacityRequirement requirement) {
        if (!requirement.containerRequirements().isEmpty()) {
            for (ContainerRequirement requested : requirement.containerRequirements()) {
                ContainerCapacity capacity = findContainerCapacity(requested.containerType());
                if (capacity == null || capacity.availableQuantity() < requested.quantity()) {
                    return false;
                }
            }
            return true;
        }
        boolean weightOk = requirement.totalWeightKg() == null
                || (availableWeightKg != null && availableWeightKg.compareTo(requirement.totalWeightKg()) >= 0);
        boolean volumeOk = requirement.totalVolumeCbm() == null
                || (availableVolumeCbm != null && availableVolumeCbm.compareTo(requirement.totalVolumeCbm()) >= 0);
        return weightOk && volumeOk;
    }

    /** Pure — no side effect. Caller CASes the result in; see MatchingServiceImpl.reserve(). */
    public CapacityOffering decrement(CapacityRequirement requirement) {
        List<ContainerCapacity> updatedContainers = containerCapacities;
        if (!requirement.containerRequirements().isEmpty()) {
            updatedContainers = new ArrayList<>();
            for (ContainerCapacity capacity : containerCapacities) {
                int requested = requirement.containerRequirements().stream()
                        .filter(r -> r.containerType().equals(capacity.containerType()))
                        .mapToInt(ContainerRequirement::quantity)
                        .sum();
                updatedContainers.add(capacity.withAvailableQuantity(capacity.availableQuantity() - requested));
            }
        }

        BigDecimal updatedWeight = requirement.totalWeightKg() != null && availableWeightKg != null
                ? availableWeightKg.subtract(requirement.totalWeightKg())
                : availableWeightKg;
        BigDecimal updatedVolume = requirement.totalVolumeCbm() != null && availableVolumeCbm != null
                ? availableVolumeCbm.subtract(requirement.totalVolumeCbm())
                : availableVolumeCbm;

        return new CapacityOffering(tenantId, offeringId, operatorId, mode, originNodeId, destinationNodeId,
                updatedContainers, totalWeightKg, updatedWeight, totalVolumeCbm, updatedVolume,
                rateAmount, rateCurrency, validFrom, validUntil, status, version + 1, createdAt, Instant.now());
    }

    private ContainerCapacity findContainerCapacity(String containerType) {
        return containerCapacities.stream()
                .filter(c -> c.containerType().equals(containerType))
                .findFirst()
                .orElse(null);
    }
}

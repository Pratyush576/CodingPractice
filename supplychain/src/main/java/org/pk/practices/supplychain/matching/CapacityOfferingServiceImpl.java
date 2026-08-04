package org.pk.practices.supplychain.matching;

import org.pk.practices.supplychain.auth.AuthenticatedParty;
import org.pk.practices.supplychain.booking.TransportMode;
import org.pk.practices.supplychain.common.AuthorizationException;
import org.pk.practices.supplychain.common.ValidationException;
import org.pk.practices.supplychain.party.PartyRole;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class CapacityOfferingServiceImpl implements CapacityOfferingService {

    private final CapacityOfferingRepository repository;

    public CapacityOfferingServiceImpl(CapacityOfferingRepository repository) {
        this.repository = repository;
    }

    @Override
    public CapacityOffering create(AuthenticatedParty actor, CreateCapacityOfferingRequest request) {
        if (actor.role() != PartyRole.OPERATOR) {
            throw new AuthorizationException("Only an Operator account may create a capacity offering");
        }

        List<String> violations = new ArrayList<>();
        requireNonBlank(violations, "originNodeId", request.originNodeId());
        requireNonBlank(violations, "destinationNodeId", request.destinationNodeId());

        TransportMode mode = parseEnum(violations, "mode", request.mode(), TransportMode.class);
        if (mode == TransportMode.ANY) {
            violations.add("mode must be a concrete mode, not ANY — an offering is always one specific mode");
        }

        if (request.rateAmount() == null || request.rateAmount().signum() <= 0) {
            violations.add("rateAmount is required and must be positive");
        }
        requireNonBlank(violations, "rateCurrency", request.rateCurrency());

        List<ContainerCapacity> containerCapacities = new ArrayList<>();
        boolean hasContainerCapacity = request.containerCapacities() != null && !request.containerCapacities().isEmpty();
        if (hasContainerCapacity) {
            containerCapacities = toContainerCapacities(violations, request.containerCapacities());
        }
        boolean hasWeightVolumeCapacity = request.totalWeightKg() != null || request.totalVolumeCbm() != null;
        if (!hasContainerCapacity && !hasWeightVolumeCapacity) {
            violations.add("at least one of containerCapacities or totalWeightKg/totalVolumeCbm is required");
        }

        Instant validFrom = parseInstant(violations, "validFrom", request.validFrom());
        Instant validUntil = parseInstant(violations, "validUntil", request.validUntil());

        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }

        Instant now = Instant.now();
        CapacityOffering offering = new CapacityOffering(
                actor.tenantId(),
                UUID.randomUUID().toString(),
                actor.partyId(),
                mode,
                request.originNodeId(),
                request.destinationNodeId(),
                containerCapacities,
                request.totalWeightKg(),
                request.totalWeightKg(),
                request.totalVolumeCbm(),
                request.totalVolumeCbm(),
                request.rateAmount(),
                request.rateCurrency(),
                validFrom,
                validUntil,
                CapacityOfferingStatus.ACTIVE,
                0L,
                now,
                now
        );

        repository.insert(offering);
        return offering;
    }

    @Override
    public List<CapacityOffering> listOwn(AuthenticatedParty actor) {
        if (actor.role() != PartyRole.OPERATOR) {
            throw new AuthorizationException("Only an Operator account manages capacity offerings");
        }
        return repository.findAllForOperator(actor.partyId());
    }

    private List<ContainerCapacity> toContainerCapacities(List<String> violations, List<ContainerCapacityRequest> requests) {
        List<ContainerCapacity> result = new ArrayList<>();
        for (ContainerCapacityRequest r : requests) {
            if (r.containerType() == null || r.containerType().isBlank()) {
                violations.add("containerCapacities: containerType is required");
            } else if (r.totalQuantity() == null || r.totalQuantity() <= 0) {
                violations.add("containerCapacities: totalQuantity must be positive for " + r.containerType());
            } else {
                result.add(new ContainerCapacity(r.containerType(), r.totalQuantity(), r.totalQuantity()));
            }
        }
        return result;
    }

    private static void requireNonBlank(List<String> violations, String field, String value) {
        if (value == null || value.isBlank()) {
            violations.add(field + " is required");
        }
    }

    private static <E extends Enum<E>> E parseEnum(List<String> violations, String field, String value, Class<E> type) {
        if (value == null || value.isBlank()) {
            violations.add(field + " is required");
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            violations.add(field + " must be one of " + Arrays.toString(type.getEnumConstants()) + ", got '" + value + "'");
            return null;
        }
    }

    private static Instant parseInstant(List<String> violations, String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeException e) {
            violations.add(field + " must be an ISO-8601 instant (e.g. 2026-03-05T00:00:00Z), got '" + value + "'");
            return null;
        }
    }
}

package org.pk.practices.supplychain.booking;

import org.pk.practices.supplychain.auth.AuthenticatedParty;
import org.pk.practices.supplychain.common.AuthorizationException;
import org.pk.practices.supplychain.common.ConflictException;
import org.pk.practices.supplychain.common.DomainEvent;
import org.pk.practices.supplychain.common.DomainException;
import org.pk.practices.supplychain.common.ValidationException;
import org.pk.practices.supplychain.matching.CapacityOffering;
import org.pk.practices.supplychain.matching.CapacityRequirement;
import org.pk.practices.supplychain.matching.MatchingService;
import org.pk.practices.supplychain.matching.ReservationResult;
import org.pk.practices.supplychain.party.PartyRole;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * LLD.md §2 — createDraft()/submit() algorithms as documented, plus the
 * role/ownership rules this UI needs: only a SHIPPER may createDraft(), a
 * SHIPPER may only read or mutate their own bookings, and an OPERATOR has
 * full tenant-wide access. tenantId is never taken from a caller — it comes
 * from the authenticated actor, same as shipperId.
 */
public class BookingServiceImpl implements BookingService {

    private final BookingRepository repository;
    private final MatchingService matchingService;

    public BookingServiceImpl(BookingRepository repository, MatchingService matchingService) {
        this.repository = repository;
        this.matchingService = matchingService;
    }

    @Override
    public Booking createDraft(AuthenticatedParty actor, CreateBookingRequest request) {
        if (actor.role() != PartyRole.SHIPPER) {
            throw new AuthorizationException("Only a Shipper account may create a booking");
        }

        List<String> violations = new ArrayList<>();

        requireNonBlank(violations, "consigneeId", request.consigneeId());
        requireNonBlank(violations, "originNodeId", request.originNodeId());
        requireNonBlank(violations, "destinationNodeId", request.destinationNodeId());

        TransportMode modePreference = parseEnum(violations, "modePreference", request.modePreference(), TransportMode.class);
        Incoterm incoterm = parseEnum(violations, "incoterm", request.incoterm(), Incoterm.class);
        LoadType loadType = parseEnum(violations, "loadType", request.loadType(), LoadType.class);

        if (incoterm != null && modePreference != null && incoterm.isOceanOnly()
                && modePreference != TransportMode.OCEAN && modePreference != TransportMode.ANY) {
            violations.add(incoterm + " requires modePreference OCEAN (or ANY), got " + modePreference);
        }

        List<ContainerRequirement> containerRequirements = List.of();
        if (loadType == LoadType.FCL) {
            if (request.containerRequirements() == null || request.containerRequirements().isEmpty()) {
                violations.add("loadType FCL requires a non-empty containerRequirements list");
            } else {
                containerRequirements = toContainerRequirements(violations, request.containerRequirements());
            }
        } else if (loadType == LoadType.LCL || loadType == LoadType.BREAKBULK) {
            if (request.totalWeightKg() == null && request.totalVolumeCbm() == null) {
                violations.add("loadType " + loadType + " requires totalWeightKg or totalVolumeCbm");
            }
        }

        if (request.cargoLineItems() == null || request.cargoLineItems().isEmpty()) {
            violations.add("at least one cargo line item is required");
        } else {
            validateCargoLineItems(violations, request.cargoLineItems());
        }

        Instant requiredPickupBy = parseInstant(violations, "requiredPickupBy", request.requiredPickupBy());
        Instant requiredDeliveryBy = parseInstant(violations, "requiredDeliveryBy", request.requiredDeliveryBy());

        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }

        Instant now = Instant.now();
        Booking booking = new Booking(
                actor.tenantId(),
                UUID.randomUUID().toString(),
                BookingStatus.DRAFT,
                modePreference,
                incoterm,
                loadType,
                request.originNodeId(),
                request.destinationNodeId(),
                actor.partyId(),
                request.consigneeId(),
                request.notifyPartyId(),
                request.contractId(),
                requiredPickupBy,
                requiredDeliveryBy,
                request.totalWeightKg(),
                request.totalVolumeCbm(),
                containerRequirements,
                toCargoLineItems(request.cargoLineItems()),
                null,
                0L,
                now,
                now
        );

        repository.insertDraft(booking);
        return booking;
    }

    @Override
    public Booking submit(AuthenticatedParty actor, String bookingId) {
        Booking current = requireOwnedBooking(actor, bookingId);
        if (current.status() != BookingStatus.DRAFT) {
            throw new DomainException("ILLEGAL_TRANSITION",
                    "Booking " + bookingId + " is " + current.status() + ", not DRAFT — cannot submit");
        }
        // Nothing required can have gone missing since createDraft() — this service is the
        // only writer of a DRAFT — so re-validation here is a no-op by construction, not skipped.

        Booking updated = current.withStatus(BookingStatus.SUBMITTED, Instant.now());

        Map<String, Object> payload = new HashMap<>();
        payload.put("tenantId", current.tenantId());
        payload.put("bookingId", bookingId);
        payload.put("incoterm", updated.incoterm().name());
        payload.put("modePreference", updated.modePreference().name());
        DomainEvent event = DomainEvent.of("Booking", bookingId, "BookingSubmitted", payload);

        if (!repository.save(current, updated, event)) {
            throw new ConflictException("Booking " + bookingId + " was concurrently modified — reload and retry");
        }
        return updated;
    }

    @Override
    public Booking amend(AuthenticatedParty actor, String bookingId, BookingAmendment amendment) {
        Booking current = requireOwnedBooking(actor, bookingId);
        if (current.version() != amendment.expectedVersion()) {
            throw new ConflictException("Booking " + bookingId + " is at version " + current.version()
                    + ", amendment was based on version " + amendment.expectedVersion());
        }

        List<String> violations = new ArrayList<>();
        Instant requiredPickupBy = amendment.requiredPickupBy() != null
                ? parseInstant(violations, "requiredPickupBy", amendment.requiredPickupBy())
                : current.requiredPickupBy();
        Instant requiredDeliveryBy = amendment.requiredDeliveryBy() != null
                ? parseInstant(violations, "requiredDeliveryBy", amendment.requiredDeliveryBy())
                : current.requiredDeliveryBy();
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }

        String notifyPartyId = amendment.notifyPartyId() != null ? amendment.notifyPartyId() : current.notifyPartyId();
        List<CargoLineItem> cargoLineItems = amendment.cargoLineItems() != null
                ? toCargoLineItems(amendment.cargoLineItems())
                : current.cargoLineItems();

        Booking updated = current.withAmendment(requiredPickupBy, requiredDeliveryBy, notifyPartyId, cargoLineItems, Instant.now());

        // No event published — an amendment to a still-DRAFT/SUBMITTED booking isn't
        // something any downstream reactor in DESIGN.md §5 currently needs to know about.
        if (!repository.save(current, updated, null)) {
            throw new ConflictException("Booking " + bookingId + " was concurrently modified — reload and retry");
        }
        return updated;
    }

    @Override
    public Booking cancel(AuthenticatedParty actor, String bookingId, String reason) {
        Booking current = requireOwnedBooking(actor, bookingId);
        if (current.status() == BookingStatus.CANCELLED) {
            return current;
        }
        if (current.status() == BookingStatus.CONFIRMED) {
            throw new DomainException("ILLEGAL_TRANSITION", "Booking " + bookingId
                    + " is CONFIRMED — cancelling a confirmed Plan goes through Replanning, not Booking Service");
        }
        Booking updated = current.withStatus(BookingStatus.CANCELLED, Instant.now());
        if (!repository.save(current, updated, null)) {
            throw new ConflictException("Booking " + bookingId + " was concurrently modified — reload and retry");
        }
        return updated;
    }

    @Override
    public Booking reserveCapacity(AuthenticatedParty actor, String bookingId, String offeringId) {
        Booking current = requireOwnedBooking(actor, bookingId);
        if (current.status() != BookingStatus.SUBMITTED) {
            throw new DomainException("ILLEGAL_TRANSITION",
                    "Booking " + bookingId + " is " + current.status() + ", not SUBMITTED — cannot reserve capacity");
        }

        CapacityRequirement requirement = CapacityRequirement.from(current);
        ReservationResult result = matchingService.reserve(offeringId, requirement);

        return switch (result) {
            case ReservationResult.Success ignored -> {
                Booking updated = current.withConfirmedCapacity(offeringId, Instant.now());
                Map<String, Object> payload = new HashMap<>();
                payload.put("tenantId", current.tenantId());
                payload.put("bookingId", bookingId);
                payload.put("capacityOfferingId", offeringId);
                DomainEvent event = DomainEvent.of("Booking", bookingId, "BookingConfirmed", payload);
                if (!repository.save(current, updated, event)) {
                    // The offering's capacity is already decremented and committed at this point —
                    // BookingRepository and CapacityOfferingRepository each run their own transaction,
                    // so there's no single transaction spanning both tables. A lost race here leaves
                    // capacity consumed without the booking reflecting it — a real Planning Engine would
                    // close this gap with a saga/compensating step; this slice surfaces it as a
                    // conflict to retry rather than silently losing track of it.
                    throw new ConflictException("Booking " + bookingId + " was concurrently modified after capacity "
                            + "was reserved on " + offeringId + " — the reservation stands; reload the booking and retry");
                }
                yield updated;
            }
            case ReservationResult.Insufficient ignored -> throw new DomainException("INSUFFICIENT_CAPACITY",
                    "Capacity offering " + offeringId + " no longer has enough capacity for booking " + bookingId);
            case ReservationResult.Contention ignored -> throw new ConflictException(
                    "Capacity offering " + offeringId + " is under heavy contention — retry");
        };
    }

    @Override
    public Optional<Booking> get(AuthenticatedParty actor, String bookingId) {
        return repository.find(bookingId)
                .filter(booking -> canAccess(actor, booking));
    }

    @Override
    public List<CapacityOffering> findCandidates(AuthenticatedParty actor, String bookingId) {
        Booking booking = requireOwnedBooking(actor, bookingId);
        return matchingService.findCandidates(booking);
    }

    @Override
    public List<Booking> list(AuthenticatedParty actor, String status) {
        BookingStatus statusFilter = null;
        if (status != null && !status.isBlank()) {
            try {
                statusFilter = BookingStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ValidationException(List.of(
                        "status must be one of " + Arrays.toString(BookingStatus.values()) + ", got '" + status + "'"));
            }
        }
        // OPERATOR is tenant-agnostic — sees every booking, from every tenant; SHIPPER is
        // scoped to their own, everywhere they might have created one.
        String shipperIdFilter = actor.role() == PartyRole.SHIPPER ? actor.partyId() : null;
        List<Booking> bookings = repository.findAll(statusFilter, shipperIdFilter);

        // Same rule as canAccess(): an Operator never sees a DRAFT — still private, unsubmitted
        // work — regardless of whether "all statuses" or an explicit ?status=DRAFT was asked for.
        if (actor.role() == PartyRole.OPERATOR) {
            bookings = bookings.stream().filter(b -> b.status() != BookingStatus.DRAFT).toList();
        }
        return bookings;
    }

    /** Fetches the booking and enforces ownership in one place — every mutating method needs both. */
    private Booking requireOwnedBooking(AuthenticatedParty actor, String bookingId) {
        Booking booking = repository.find(bookingId)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "No booking " + bookingId));
        if (!canAccess(actor, booking)) {
            throw new AuthorizationException("Booking " + bookingId + " does not belong to this account");
        }
        return booking;
    }

    /**
     * A Shipper always sees their own booking, any status — including their own DRAFT, still
     * being edited. An Operator sees everything else, EXCEPT another Shipper's DRAFT: a draft
     * is private in-progress work that hasn't been submitted for operational visibility yet.
     */
    private boolean canAccess(AuthenticatedParty actor, Booking booking) {
        if (booking.shipperId().equals(actor.partyId())) {
            return true;
        }
        return actor.role() == PartyRole.OPERATOR && booking.status() != BookingStatus.DRAFT;
    }

    /**
     * The {@code cargo_line_items} table (schema.sql) requires description,
     * quantity, and unitOfMeasure — checked here, at the same point every
     * other requirement is, rather than surfacing as a raw NOT NULL
     * constraint violation from Postgres the first time one is missing.
     */
    private void validateCargoLineItems(List<String> violations, List<CargoLineItemRequest> requests) {
        for (int i = 0; i < requests.size(); i++) {
            CargoLineItemRequest item = requests.get(i);
            String prefix = "cargoLineItems[" + i + "]";
            if (item.description() == null || item.description().isBlank()) {
                violations.add(prefix + ".description is required");
            }
            if (item.quantity() == null) {
                violations.add(prefix + ".quantity is required");
            } else if (item.quantity().signum() <= 0) {
                violations.add(prefix + ".quantity must be positive");
            }
            if (item.unitOfMeasure() == null || item.unitOfMeasure().isBlank()) {
                violations.add(prefix + ".unitOfMeasure is required");
            }
        }
    }

    private List<CargoLineItem> toCargoLineItems(List<CargoLineItemRequest> requests) {
        List<CargoLineItem> items = new ArrayList<>();
        for (CargoLineItemRequest r : requests) {
            items.add(new CargoLineItem(
                    UUID.randomUUID().toString(),
                    r.hsCode(), r.description(), r.countryOfOrigin(), r.quantity(), r.unitOfMeasure(),
                    r.lineWeightKg(), r.lineValueAmount(), r.lineValueCurrency(), r.dgClass(), r.unNumber(), r.packingGroup()
            ));
        }
        return items;
    }

    private List<ContainerRequirement> toContainerRequirements(List<String> violations, List<ContainerRequirementRequest> requests) {
        List<ContainerRequirement> result = new ArrayList<>();
        for (ContainerRequirementRequest r : requests) {
            if (r.containerType() == null || r.containerType().isBlank()) {
                violations.add("containerRequirements: containerType is required");
            } else if (r.quantity() == null || r.quantity() <= 0) {
                violations.add("containerRequirements: quantity must be positive for " + r.containerType());
            } else {
                result.add(new ContainerRequirement(r.containerType(), r.quantity()));
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

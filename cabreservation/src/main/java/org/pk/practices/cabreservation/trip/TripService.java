package org.pk.practices.cabreservation.trip;

import org.pk.practices.cabreservation.common.ConflictException;
import org.pk.practices.cabreservation.common.DomainException;
import org.pk.practices.cabreservation.driver.DriverService;
import org.pk.practices.cabreservation.eventbus.DomainEvent;
import org.pk.practices.cabreservation.eventbus.EventBus;
import org.pk.practices.cabreservation.eventbus.EventTypes;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * DESIGN.md §4.1/§4.4. {@code requestTrip} inserts and publishes, then
 * returns immediately — it never waits on matching, which is what makes the
 * "immediate ack, not the match result" shape (§4.1) real. No fare here per
 * the Phase 1 scoping decision: {@code fareEstimate}/{@code fareFinal} stay
 * null until Phase 2's PricingStrategy lands.
 */
public class TripService {

    private final TripRepository tripRepository;
    private final DriverService driverService;
    private final EventBus eventBus;

    public TripService(TripRepository tripRepository, DriverService driverService, EventBus eventBus) {
        this.tripRepository = tripRepository;
        this.driverService = driverService;
        this.eventBus = eventBus;
    }

    public Trip requestTrip(String riderId, TripRequest request) {
        Trip trip = new Trip(
                UUID.randomUUID().toString(),
                riderId,
                null,
                TripStatus.REQUESTED,
                request.pickupLat(), request.pickupLng(),
                request.dropoffLat(), request.dropoffLng(),
                null, null, null, null,
                0,
                Instant.now(), null, null
        );
        tripRepository.insert(trip);
        eventBus.publish(DomainEvent.of(EventTypes.TRIP_REQUESTED, trip.tripId(), Map.of()));
        return trip;
    }

    public Optional<Trip> get(String tripId) {
        return tripRepository.find(tripId);
    }

    public Optional<Trip> findActiveForDriver(String driverId) {
        return tripRepository.findActiveForDriver(driverId);
    }

    public List<Trip> listForRider(String riderId) {
        return tripRepository.findByRiderId(riderId);
    }

    public List<Trip> listForDriver(String driverId) {
        return tripRepository.findByDriverId(driverId);
    }

    public Trip markArrived(String tripId) {
        return transition(tripId, Set.of(TripStatus.DRIVER_ARRIVING), TripStatus.ARRIVED);
    }

    public Trip markStarted(String tripId) {
        return transition(tripId, Set.of(TripStatus.ARRIVED), TripStatus.IN_PROGRESS);
    }

    /**
     * A trip's driver is ON_TRIP from the moment they accept until this
     * call — releasing them back to AVAILABLE (and back into the
     * geo-index's searchable pool) is this method's job just as much as
     * flipping the trip's own status. Missing this would strand a driver
     * off the matchable supply pool forever after their very first trip.
     */
    public Trip markCompleted(String tripId) {
        Trip trip = require(tripId);
        if (trip.status() != TripStatus.IN_PROGRESS) {
            throw new DomainException("ILLEGAL_TRANSITION", "Cannot move trip " + tripId + " from " + trip.status() + " to COMPLETED");
        }
        if (!tripRepository.recordCompleted(trip)) {
            throw new ConflictException("Trip " + tripId + " was concurrently modified — reload and retry");
        }
        driverService.completeTrip(trip.driverId());
        eventBus.publish(DomainEvent.of(EventTypes.TRIP_COMPLETED, tripId, Map.of("driverId", trip.driverId())));
        return tripRepository.find(tripId).orElseThrow();
    }

    /**
     * Same driver-release concern as {@link #markCompleted} — a cancellation past MATCHED
     * already has an assigned driver to free up. A cancellation during MATCHING has no
     * assigned driver yet, but if there's an outstanding, not-yet-accepted offer, that
     * driver is sitting in PENDING_OFFER and needs releasing too — otherwise a rider
     * cancelling before the driver responds strands them there forever, since the
     * MatchOfferTimeoutSweeper only revisits trips still in MATCHING status.
     */
    public Trip cancel(String tripId, CancelledBy actor) {
        Set<TripStatus> legalFrom = actor == CancelledBy.RIDER
                ? EnumSet.of(TripStatus.REQUESTED, TripStatus.MATCHING, TripStatus.MATCHED, TripStatus.DRIVER_ARRIVING)
                : EnumSet.of(TripStatus.MATCHED, TripStatus.DRIVER_ARRIVING);
        TripStatus target = actor == CancelledBy.RIDER ? TripStatus.CANCELLED_BY_RIDER : TripStatus.CANCELLED_BY_DRIVER;
        Trip cancelled = transition(tripId, legalFrom, target);
        if (cancelled.driverId() != null) {
            driverService.completeTrip(cancelled.driverId());
        } else if (cancelled.offeredDriverId() != null) {
            driverService.releaseFromOffer(cancelled.offeredDriverId());
        }
        return cancelled;
    }

    private Trip transition(String tripId, Set<TripStatus> legalFrom, TripStatus target) {
        Trip trip = require(tripId);
        if (!legalFrom.contains(trip.status())) {
            throw new DomainException("ILLEGAL_TRANSITION",
                    "Cannot move trip " + tripId + " from " + trip.status() + " to " + target);
        }
        if (!tripRepository.compareAndSetStatus(trip, target)) {
            throw new ConflictException("Trip " + tripId + " was concurrently modified — reload and retry");
        }
        return trip.withStatus(target);
    }

    private Trip require(String tripId) {
        return tripRepository.find(tripId)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "No trip with id " + tripId));
    }
}

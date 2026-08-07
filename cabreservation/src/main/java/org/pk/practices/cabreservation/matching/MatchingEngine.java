package org.pk.practices.cabreservation.matching;

import org.pk.practices.cabreservation.common.ConflictException;
import org.pk.practices.cabreservation.common.DomainException;
import org.pk.practices.cabreservation.driver.Driver;
import org.pk.practices.cabreservation.driver.DriverRepository;
import org.pk.practices.cabreservation.driver.DriverService;
import org.pk.practices.cabreservation.eventbus.DomainEvent;
import org.pk.practices.cabreservation.eventbus.EventBus;
import org.pk.practices.cabreservation.eventbus.EventTypes;
import org.pk.practices.cabreservation.geo.GeoIndex;
import org.pk.practices.cabreservation.geo.H3CellResolver;
import org.pk.practices.cabreservation.geo.NearbyDriver;
import org.pk.practices.cabreservation.trip.Trip;
import org.pk.practices.cabreservation.trip.TripRepository;
import org.pk.practices.cabreservation.trip.TripStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DESIGN.md §4.3 — the double-dispatch race, fixed for real. Two concurrent
 * dispatches racing on the same driver both call
 * {@link DriverService#tryReserveForOffer}, which is backed by a
 * DB-level compare-and-swap; exactly one wins, the loser moves to the next
 * candidate. Never re-offers a driver that was just lost.
 */
public class MatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);

    /** Radius tiers to try in order before giving up — DESIGN.md §4.2's "expand the search" step. */
    private static final double[] RADII_KM = {2.0, 5.0, 10.0};
    private static final int MAX_CANDIDATES = 5;
    static final Duration OFFER_TIMEOUT = Duration.ofSeconds(10);

    private final TripRepository tripRepository;
    private final DriverRepository driverRepository;
    private final DriverService driverService;
    private final GeoIndex geoIndex;
    private final H3CellResolver h3CellResolver;
    private final EventBus eventBus;

    public MatchingEngine(TripRepository tripRepository, DriverRepository driverRepository, DriverService driverService,
                           GeoIndex geoIndex, H3CellResolver h3CellResolver, EventBus eventBus) {
        this.tripRepository = tripRepository;
        this.driverRepository = driverRepository;
        this.driverService = driverService;
        this.geoIndex = geoIndex;
        this.h3CellResolver = h3CellResolver;
        this.eventBus = eventBus;
        eventBus.subscribe(EventTypes.TRIP_REQUESTED, this::onTripRequested);
    }

    private void onTripRequested(DomainEvent event) {
        String tripId = event.tripId();
        Trip trip = tripRepository.find(tripId).orElse(null);
        if (trip == null || trip.status() != TripStatus.REQUESTED) {
            return;
        }
        if (!tripRepository.compareAndSetStatus(trip, TripStatus.MATCHING)) {
            log.warn("Lost CAS moving trip {} to MATCHING — leaving it alone", tripId);
            return;
        }
        attemptDispatch(tripId, Set.of(), 0);
    }

    /**
     * For each candidate in distance order, tries to reserve them; on a
     * lost race, moves to the next candidate without re-offering the one
     * just lost. Exhausting every candidate at every radius tier resolves
     * the trip to NO_DRIVERS_FOUND.
     */
    private void attemptDispatch(String tripId, Set<String> excludedDriverIds, int radiusTier) {
        Trip trip = tripRepository.find(tripId).orElse(null);
        if (trip == null || trip.status() != TripStatus.MATCHING) {
            return; // already resolved (matched/cancelled/timed out) via another path
        }
        if (radiusTier >= RADII_KM.length) {
            if (tripRepository.compareAndSetStatus(trip, TripStatus.NO_DRIVERS_FOUND)) {
                eventBus.publish(DomainEvent.of(EventTypes.NO_DRIVERS_FOUND, tripId, Map.of()));
            }
            return;
        }

        String regionCellId = h3CellResolver.regionCellId(trip.pickupLat(), trip.pickupLng());
        List<NearbyDriver> candidates = geoIndex.findNearby(regionCellId, trip.pickupLat(), trip.pickupLng(),
                RADII_KM[radiusTier], MAX_CANDIDATES + excludedDriverIds.size());

        for (NearbyDriver candidate : candidates) {
            if (excludedDriverIds.contains(candidate.driverId())) {
                continue;
            }
            Driver driver = driverRepository.findById(candidate.driverId()).orElse(null);
            if (driver == null) {
                continue;
            }
            if (driverService.tryReserveForOffer(driver)) {
                Instant expiresAt = Instant.now().plus(OFFER_TIMEOUT);
                tripRepository.recordOffer(trip, driver.driverId(), expiresAt);
                eventBus.publish(DomainEvent.of(EventTypes.TRIP_OFFERED, tripId, Map.of("driverId", driver.driverId())));
                return;
            }
            // Lost the CAS race to a concurrent dispatch on this same driver — try the next candidate, don't re-offer this one.
        }
        attemptDispatch(tripId, excludedDriverIds, radiusTier + 1);
    }

    public void onDriverResponded(String tripId, String driverId, boolean accepted) {
        Trip trip = tripRepository.find(tripId)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "No trip with id " + tripId));
        if (trip.status() != TripStatus.MATCHING || !driverId.equals(trip.offeredDriverId())) {
            throw new ConflictException("This offer is no longer active");
        }
        if (accepted) {
            driverService.confirmOnTrip(driverId);
            if (!tripRepository.recordMatched(trip, driverId)) {
                throw new ConflictException("Trip was concurrently modified — reload and retry");
            }
            eventBus.publish(DomainEvent.of(EventTypes.TRIP_MATCHED, tripId, Map.of("driverId", driverId)));
            // §4.4's diagram shows MATCHED -> DRIVER_ARRIVING with no separate trigger — advance it here.
            tripRepository.find(tripId).ifPresent(matched -> tripRepository.compareAndSetStatus(matched, TripStatus.DRIVER_ARRIVING));
        } else {
            releaseAndRedispatch(tripId, driverId);
        }
    }

    /** Called by MatchOfferTimeoutSweeper for an offer that expired with no response. */
    public void onOfferTimedOut(String tripId, String driverId) {
        Trip trip = tripRepository.find(tripId).orElse(null);
        if (trip == null || trip.status() != TripStatus.MATCHING || !driverId.equals(trip.offeredDriverId())) {
            return; // already resolved via another path — benign race with a driver responding right as the sweeper fires
        }
        releaseAndRedispatch(tripId, driverId);
    }

    private void releaseAndRedispatch(String tripId, String driverId) {
        driverService.releaseFromOffer(driverId);
        attemptDispatch(tripId, Set.of(driverId), 0);
    }
}

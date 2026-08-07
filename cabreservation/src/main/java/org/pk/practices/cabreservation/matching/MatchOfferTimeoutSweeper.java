package org.pk.practices.cabreservation.matching;

import org.pk.practices.cabreservation.trip.Trip;
import org.pk.practices.cabreservation.trip.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A poll loop, not a per-offer scheduled future — simpler, and naturally
 * survives a restart (nothing to reschedule; expired offers are just rows
 * the next tick picks up). Same shape as supplychain's OutboxRelay.
 */
public class MatchOfferTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(MatchOfferTimeoutSweeper.class);

    private final TripRepository tripRepository;
    private final MatchingEngine matchingEngine;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public MatchOfferTimeoutSweeper(TripRepository tripRepository, MatchingEngine matchingEngine) {
        this.tripRepository = tripRepository;
        this.matchingEngine = matchingEngine;
    }

    public void start(long intervalMs) {
        scheduler.scheduleWithFixedDelay(this::sweep, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void sweep() {
        try {
            for (Trip trip : tripRepository.findExpiredOffers(Instant.now())) {
                matchingEngine.onOfferTimedOut(trip.tripId(), trip.offeredDriverId());
            }
        } catch (Exception e) {
            log.error("Sweep failed — will retry next tick", e);
        }
    }

    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

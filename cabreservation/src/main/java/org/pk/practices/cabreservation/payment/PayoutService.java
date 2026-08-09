package org.pk.practices.cabreservation.payment;

import org.pk.practices.cabreservation.eventbus.DomainEvent;
import org.pk.practices.cabreservation.eventbus.EventBus;
import org.pk.practices.cabreservation.eventbus.EventTypes;
import org.pk.practices.cabreservation.payment.gateway.PayoutProvider;
import org.pk.practices.cabreservation.trip.Trip;
import org.pk.practices.cabreservation.trip.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

/**
 * DESIGN.md §4.7's Accounts Payable branch — subscribes to the same
 * {@code TRIP_COMPLETED} event {@code PaymentService} does and settles
 * independently: a declined rider charge must never hold a driver's
 * earnings hostage, and vice versa (the {@code par} block in §4.7's
 * sequence diagram). Per-trip instant payout, the chosen decision in §4.7
 * (not batched) — the payout is just another leg of the same event
 * handler, no separate accrual ledger. Idempotent via
 * {@code payouts.trip_id UNIQUE}, the same discipline as PaymentService.
 */
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    /** §4.7: payoutAmount = finalFare − commission. A flat platform take rate — no product-line/promo variance modeled. */
    private static final double PLATFORM_COMMISSION_RATE = 0.20;

    private final TripRepository tripRepository;
    private final PayoutRepository payoutRepository;
    private final PayoutProvider payoutProvider;

    public PayoutService(TripRepository tripRepository, PayoutRepository payoutRepository,
                          PayoutProvider payoutProvider, EventBus eventBus) {
        this.tripRepository = tripRepository;
        this.payoutRepository = payoutRepository;
        this.payoutProvider = payoutProvider;
        eventBus.subscribe(EventTypes.TRIP_COMPLETED, this::onTripCompleted);
    }

    private void onTripCompleted(DomainEvent event) {
        String tripId = event.tripId();
        Trip trip = tripRepository.find(tripId).orElse(null);
        if (trip == null || trip.fareFinal() == null || trip.driverId() == null) {
            log.warn("TRIP_COMPLETED for {} with no fareFinal/driver on record — skipping payout", tripId);
            return;
        }

        double payoutAmount = Math.round(trip.fareFinal() * (1 - PLATFORM_COMMISSION_RATE) * 100.0) / 100.0;
        PayoutProvider.TransferResult result = payoutProvider.transfer(trip.driverId(), tripId, payoutAmount);
        Payout payout = new Payout(
                UUID.randomUUID().toString(),
                tripId,
                trip.driverId(),
                payoutAmount,
                result.success() ? PayoutStatus.PAID : PayoutStatus.FAILED,
                result.reference(),
                Instant.now()
        );

        boolean inserted = payoutRepository.insert(payout);
        if (!inserted) {
            log.info("Trip {} was already paid out — ignoring duplicate TRIP_COMPLETED", tripId);
        }
    }
}

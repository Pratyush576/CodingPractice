package org.pk.practices.cabreservation.payment;

import org.pk.practices.cabreservation.eventbus.DomainEvent;
import org.pk.practices.cabreservation.eventbus.EventBus;
import org.pk.practices.cabreservation.eventbus.EventTypes;
import org.pk.practices.cabreservation.payment.gateway.PaymentGateway;
import org.pk.practices.cabreservation.trip.Trip;
import org.pk.practices.cabreservation.trip.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

/**
 * DESIGN.md §4.7's Accounts Receivable branch — subscribes to
 * {@code TRIP_COMPLETED} and charges the rider for the trip's fareFinal
 * (already computed and persisted by {@code TripService.markCompleted}
 * before this event fires). Idempotent via {@code payments.trip_id UNIQUE}:
 * a redelivered event or a concurrent retry just hits the unique-constraint
 * violation in {@link PaymentRepository#insert} and no-ops, rather than
 * double-charging — the same DB-level guarantee this module already leans
 * on for driver assignment (§4.3).
 */
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final TripRepository tripRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;

    public PaymentService(TripRepository tripRepository, PaymentRepository paymentRepository,
                           PaymentGateway paymentGateway, EventBus eventBus) {
        this.tripRepository = tripRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        eventBus.subscribe(EventTypes.TRIP_COMPLETED, this::onTripCompleted);
    }

    private void onTripCompleted(DomainEvent event) {
        String tripId = event.tripId();
        Trip trip = tripRepository.find(tripId).orElse(null);
        if (trip == null || trip.fareFinal() == null) {
            log.warn("TRIP_COMPLETED for {} with no fareFinal on record — skipping charge", tripId);
            return;
        }

        PaymentGateway.ChargeResult result = paymentGateway.charge(trip.riderId(), tripId, trip.fareFinal());
        Payment payment = new Payment(
                UUID.randomUUID().toString(),
                tripId,
                trip.fareFinal(),
                result.success() ? PaymentStatus.CHARGED : PaymentStatus.DECLINED,
                result.reference(),
                Instant.now()
        );

        boolean inserted = paymentRepository.insert(payment);
        if (!inserted) {
            log.info("Trip {} was already charged — ignoring duplicate TRIP_COMPLETED", tripId);
        }
    }
}

package org.pk.practices.cabreservation.payment;

import java.util.Optional;

public interface PaymentRepository {
    /** Idempotent — returns false (not an error) if a payment for this trip already exists; payments.trip_id UNIQUE is the real guarantee, not this check. */
    boolean insert(Payment payment);

    Optional<Payment> findByTripId(String tripId);
}

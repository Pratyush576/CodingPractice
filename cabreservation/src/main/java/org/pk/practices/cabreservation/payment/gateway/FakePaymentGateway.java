package org.pk.practices.cabreservation.payment.gateway;

import java.util.UUID;

/**
 * Simulates a payment processor — always succeeds, no real money moves.
 * Swappable for a real Stripe/bank-rail-backed adapter later without
 * touching {@code PaymentService} at all (DESIGN.md §12).
 */
public class FakePaymentGateway implements PaymentGateway {
    @Override
    public ChargeResult charge(String riderId, String tripId, double amount) {
        return new ChargeResult(true, "fake_charge_" + UUID.randomUUID(), null);
    }
}

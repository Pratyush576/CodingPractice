package org.pk.practices.cabreservation.payment.gateway;

/**
 * Adapter — pluggable payment processor (DESIGN.md §2/§4.7). {@code amount}
 * is charged against the rider's account; this interface doesn't carry a
 * payment method, since no real processor is wired up yet — a future
 * implementation charging Stripe/etc. would need one.
 */
public interface PaymentGateway {
    ChargeResult charge(String riderId, String tripId, double amount);

    record ChargeResult(boolean success, String reference, String failureReason) {}
}

package org.pk.practices.cabreservation.payment.gateway;

/** Adapter — pluggable payout/bank-transfer processor (DESIGN.md §2/§4.7's Accounts Payable branch). */
public interface PayoutProvider {
    TransferResult transfer(String driverId, String tripId, double amount);

    record TransferResult(boolean success, String reference, String failureReason) {}
}

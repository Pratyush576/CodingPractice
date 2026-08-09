package org.pk.practices.cabreservation.payment.gateway;

import java.util.UUID;

/** Simulates a bank-transfer/payout rail — always succeeds, no real money moves. Swappable later without touching PayoutService. */
public class FakePayoutProvider implements PayoutProvider {
    @Override
    public TransferResult transfer(String driverId, String tripId, double amount) {
        return new TransferResult(true, "fake_payout_" + UUID.randomUUID(), null);
    }
}

package org.pk.practices.cabreservation.rider;

import java.time.Instant;

/** DESIGN.md §3 Domain Model. */
public record Rider(
        String riderId,
        String name,
        String email,
        String passwordHash,
        String defaultPaymentMethodId,
        Double rating,
        Instant createdAt
) {}

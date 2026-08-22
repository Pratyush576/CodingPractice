package org.pk.practices.servicesmarketplace.customer;

import java.time.Instant;

/** DESIGN.md §3 Domain Model. */
public record Customer(
        String customerId,
        String name,
        String email,
        String passwordHash,
        String defaultPaymentMethodId,
        Instant createdAt
) {}

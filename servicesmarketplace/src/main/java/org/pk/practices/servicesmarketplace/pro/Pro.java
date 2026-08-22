package org.pk.practices.servicesmarketplace.pro;

import java.time.Instant;

/** DESIGN.md §3 Domain Model. {@code verificationStatus} gates Lead eligibility (§1.7, §4.2). */
public record Pro(
        String proId,
        String businessName,
        String email,
        String passwordHash,
        VerificationStatus verificationStatus,
        Double rating,
        Integer yearsInBusiness,
        Instant createdAt
) {}

package org.pk.practices.supplychain.matching;

import org.pk.practices.supplychain.auth.AuthenticatedParty;

import java.util.List;

/** Supply-side management — LLD.md §4's Matching Engine needs something to search; this is what creates it. */
public interface CapacityOfferingService {
    /** OPERATOR only. */
    CapacityOffering create(AuthenticatedParty actor, CreateCapacityOfferingRequest request);

    /** OPERATOR only — every offering they've created, any status. */
    List<CapacityOffering> listOwn(AuthenticatedParty actor);
}

package org.pk.practices.supplychain.party;

import java.util.List;
import java.util.Optional;

public interface PartyRepository {
    /** @throws org.pk.practices.supplychain.common.ConflictException if the email is already registered */
    void insert(Party party);

    Optional<Party> findByEmail(String email);

    Optional<Party> findById(String partyId);

    /** Used at registration to warn when a tenantId is brand-new — a free-text field with no other validation. */
    boolean tenantExists(String tenantId);

    /** Every tenantId with at least one account — backs the registration form's "join an existing tenant" picker. */
    List<String> listDistinctTenantIds();
}

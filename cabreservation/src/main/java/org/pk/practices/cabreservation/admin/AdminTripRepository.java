package org.pk.practices.cabreservation.admin;

import java.time.Instant;
import java.util.List;

public interface AdminTripRepository {
    /** Every trip created since {@code since}, most recent first, joined with counterparty names and settlement outcome. Status/text filtering happens client-side over this list, same pattern as every other history view in this module. */
    List<AdminTripView> list(Instant since);
}

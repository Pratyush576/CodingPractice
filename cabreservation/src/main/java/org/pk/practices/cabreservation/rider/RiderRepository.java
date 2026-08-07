package org.pk.practices.cabreservation.rider;

import java.util.Optional;

public interface RiderRepository {
    void insert(Rider rider);
    Optional<Rider> findById(String riderId);
    Optional<Rider> findByEmail(String email);
}

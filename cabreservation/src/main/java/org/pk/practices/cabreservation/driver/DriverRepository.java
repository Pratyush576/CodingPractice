package org.pk.practices.cabreservation.driver;

import java.util.Optional;

public interface DriverRepository {
    /** Inserts the driver row and its vehicle row in one transaction (vehicles.driver_id is a FK). */
    void insert(Driver driver, Vehicle vehicle);
    Optional<Driver> findById(String driverId);
    Optional<Driver> findByEmail(String email);
    Optional<Vehicle> findVehicleByDriverId(String driverId);

    /** Lets a driver change their car marker's color after registration — no CAS needed, purely cosmetic. */
    void updateCarIcon(String driverId, CarIcon carIcon);

    /**
     * {@code UPDATE ... WHERE driver_id=? AND status=? AND version=?} — the
     * atomic compare-and-swap DESIGN.md §4.3 requires to fix the
     * double-dispatch race. Returns false on a lost race; the caller
     * (DriverService/MatchingEngine) is expected to retry against a
     * different driver, never to treat this as a hard failure.
     */
    boolean compareAndSetStatus(String driverId, DriverStatus expectedStatus, long expectedVersion, DriverStatus newStatus);

    void updateLastPing(String driverId, double lat, double lng);
}

package org.pk.practices.cabreservation.driver;

import org.pk.practices.cabreservation.common.ConflictException;
import org.pk.practices.cabreservation.common.DomainException;
import org.pk.practices.cabreservation.geo.GeoIndex;
import org.pk.practices.cabreservation.geo.H3CellResolver;

/**
 * DESIGN.md §4.3 — the single place every driver-status transition goes
 * through, so Postgres (source of truth) and Redis (searchable index) never
 * drift independently. Postgres write always precedes the Redis write; a
 * crash between the two fails in the safe direction (a missed dispatch
 * opportunity, never a double-booking) — the CAS on driver status is the
 * actual correctness guarantee regardless of ordering.
 */
public class DriverService {

    private final DriverRepository driverRepository;
    private final GeoIndex geoIndex;
    private final H3CellResolver h3CellResolver;

    public DriverService(DriverRepository driverRepository, GeoIndex geoIndex, H3CellResolver h3CellResolver) {
        this.driverRepository = driverRepository;
        this.geoIndex = geoIndex;
        this.h3CellResolver = h3CellResolver;
    }

    /** A driver can't be "available but unlocatable" — going online always requires a location. */
    public void goOnline(String driverId, double lat, double lng) {
        Driver driver = require(driverId);
        if (!driverRepository.compareAndSetStatus(driverId, DriverStatus.OFFLINE, driver.version(), DriverStatus.AVAILABLE)) {
            throw new ConflictException("Driver is not OFFLINE — cannot go online");
        }
        driverRepository.updateLastPing(driverId, lat, lng);
        geoIndex.upsertDriver(h3CellResolver.regionCellId(lat, lng), driverId, lat, lng);
    }

    public void goOffline(String driverId) {
        Driver driver = require(driverId);
        if (!driverRepository.compareAndSetStatus(driverId, DriverStatus.AVAILABLE, driver.version(), DriverStatus.OFFLINE)) {
            throw new ConflictException("Driver is not AVAILABLE — cannot go offline");
        }
        removeFromIndex(driver);
    }

    /** The double-dispatch fix (§4.3): the DB-level CAS guarantees exactly one concurrent caller wins per driver. */
    public boolean tryReserveForOffer(Driver driver) {
        boolean won = driverRepository.compareAndSetStatus(driver.driverId(), DriverStatus.AVAILABLE, driver.version(), DriverStatus.PENDING_OFFER);
        if (won) {
            removeFromIndex(driver);
        }
        return won;
    }

    public void confirmOnTrip(String driverId) {
        Driver driver = require(driverId);
        driverRepository.compareAndSetStatus(driverId, DriverStatus.PENDING_OFFER, driver.version(), DriverStatus.ON_TRIP);
        // Stays out of the index — an ON_TRIP driver isn't part of the matchable supply pool.
    }

    public void releaseFromOffer(String driverId) {
        Driver driver = require(driverId);
        if (driverRepository.compareAndSetStatus(driverId, DriverStatus.PENDING_OFFER, driver.version(), DriverStatus.AVAILABLE)) {
            reAddToIndex(require(driverId));
        }
    }

    public void completeTrip(String driverId) {
        Driver driver = require(driverId);
        if (driverRepository.compareAndSetStatus(driverId, DriverStatus.ON_TRIP, driver.version(), DriverStatus.AVAILABLE)) {
            reAddToIndex(require(driverId));
        }
    }

    /** Persists the ping always; only refreshes the geo index position if the driver is actually searchable (AVAILABLE). */
    public void recordPing(String driverId, double lat, double lng) {
        driverRepository.updateLastPing(driverId, lat, lng);
        Driver driver = require(driverId);
        if (driver.status() == DriverStatus.AVAILABLE) {
            geoIndex.upsertDriver(h3CellResolver.regionCellId(lat, lng), driverId, lat, lng);
        }
    }

    private void removeFromIndex(Driver driver) {
        if (driver.lastLat() != null && driver.lastLng() != null) {
            geoIndex.removeDriver(h3CellResolver.regionCellId(driver.lastLat(), driver.lastLng()), driver.driverId());
        }
    }

    private void reAddToIndex(Driver driver) {
        if (driver.lastLat() != null && driver.lastLng() != null) {
            geoIndex.upsertDriver(h3CellResolver.regionCellId(driver.lastLat(), driver.lastLng()), driver.driverId(), driver.lastLat(), driver.lastLng());
        }
    }

    private Driver require(String driverId) {
        return driverRepository.findById(driverId)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "No driver with id " + driverId));
    }
}

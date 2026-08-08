package org.pk.practices.cabreservation.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.pk.practices.cabreservation.auth.AccountType;
import org.pk.practices.cabreservation.auth.AuthenticatedAccount;
import org.pk.practices.cabreservation.common.AuthorizationException;
import org.pk.practices.cabreservation.driver.CarIcon;
import org.pk.practices.cabreservation.driver.Driver;
import org.pk.practices.cabreservation.driver.DriverRepository;
import org.pk.practices.cabreservation.driver.DriverService;
import org.pk.practices.cabreservation.matching.MatchingEngine;
import org.pk.practices.cabreservation.rider.RiderRepository;
import org.pk.practices.cabreservation.trip.Trip;
import org.pk.practices.cabreservation.trip.TripService;

import java.util.Map;

/**
 * Every route here requires a DRIVER-type session — {@link AuthController#requireSession}
 * runs first as a `before` filter; this class only additionally checks the
 * account *type*, since a rider token authenticating successfully doesn't
 * mean it's allowed to act as a driver.
 */
public class DriverController {

    private final DriverService driverService;
    private final MatchingEngine matchingEngine;
    private final TripService tripService;
    private final RiderRepository riderRepository;
    private final DriverRepository driverRepository;

    public DriverController(DriverService driverService, MatchingEngine matchingEngine, TripService tripService,
                             RiderRepository riderRepository, DriverRepository driverRepository) {
        this.driverService = driverService;
        this.matchingEngine = matchingEngine;
        this.tripService = tripService;
        this.riderRepository = riderRepository;
        this.driverRepository = driverRepository;
    }

    public void register(Javalin app) {
        app.post("/v1/drivers/online", this::online);
        app.post("/v1/drivers/offline", this::offline);
        app.post("/v1/drivers/location", this::location);
        app.post("/v1/drivers/offers/{tripId}/respond", this::respond);
        app.get("/v1/drivers/me/active-trip", this::activeTrip);
        app.get("/v1/drivers/me", this::me);
        app.patch("/v1/drivers/me/car-icon", this::updateCarIcon);
    }

    /** A driver's own profile — enough to pre-fill an "update my car icon" control with their current choice. */
    private void me(Context ctx) {
        String driverId = driverId(ctx);
        Driver driver = driverRepository.findById(driverId).orElseThrow(() -> new io.javalin.http.NotFoundResponse());
        VehicleInfo vehicle = driverRepository.findVehicleByDriverId(driverId).map(VehicleInfo::of).orElse(null);
        ctx.json(new DriverProfile(driver.driverId(), driver.name(), vehicle));
    }

    /** Purely cosmetic — no CAS needed, unlike the status transitions below, since nothing else in the system reads carIcon for correctness. */
    private void updateCarIcon(Context ctx) {
        UpdateCarIconRequest request = ctx.bodyAsClass(UpdateCarIconRequest.class);
        CarIcon carIcon = CarIcon.parse(request.carIcon());
        driverRepository.updateCarIcon(driverId(ctx), carIcon);
        ctx.status(204);
    }

    /** A pending offer or active trip — enriched with the rider's name, same "see the other party's details" requirement as TripController. */
    private void activeTrip(Context ctx) {
        tripService.findActiveForDriver(driverId(ctx))
                .map(this::enrich)
                .ifPresentOrElse(ctx::json, () -> ctx.status(404).json(Map.of("error", "NO_ACTIVE_TRIP")));
    }

    private TripView enrich(Trip trip) {
        PartyInfo rider = riderRepository.findById(trip.riderId())
                .map(r -> new PartyInfo(r.riderId(), r.name(), r.rating(), null, null, null))
                .orElse(null);
        PartyInfo driver = trip.driverId() == null ? null : toDriverPartyInfo(trip.driverId());
        PartyInfo offeredDriver = trip.offeredDriverId() == null ? null : toDriverPartyInfo(trip.offeredDriverId());
        return TripView.of(trip, rider, driver, offeredDriver);
    }

    private PartyInfo toDriverPartyInfo(String driverId) {
        return driverRepository.findById(driverId).map(d -> {
            VehicleInfo vehicle = driverRepository.findVehicleByDriverId(driverId).map(VehicleInfo::of).orElse(null);
            return new PartyInfo(d.driverId(), d.name(), d.rating(), vehicle, d.lastLat(), d.lastLng());
        }).orElse(null);
    }

    private void online(Context ctx) {
        LatLngRequest request = ctx.bodyAsClass(LatLngRequest.class);
        driverService.goOnline(driverId(ctx), request.lat(), request.lng());
        ctx.status(204);
    }

    private void offline(Context ctx) {
        driverService.goOffline(driverId(ctx));
        ctx.status(204);
    }

    private void location(Context ctx) {
        LatLngRequest request = ctx.bodyAsClass(LatLngRequest.class);
        driverService.recordPing(driverId(ctx), request.lat(), request.lng());
        ctx.status(204);
    }

    private void respond(Context ctx) {
        RespondRequest request = ctx.bodyAsClass(RespondRequest.class);
        matchingEngine.onDriverResponded(ctx.pathParam("tripId"), driverId(ctx), request.accept());
        ctx.status(204);
    }

    private String driverId(Context ctx) {
        AuthenticatedAccount account = ctx.attribute(AuthController.ACCOUNT_ATTRIBUTE);
        if (account.accountType() != AccountType.DRIVER) {
            throw new AuthorizationException("This endpoint requires a driver account");
        }
        return account.accountId();
    }
}

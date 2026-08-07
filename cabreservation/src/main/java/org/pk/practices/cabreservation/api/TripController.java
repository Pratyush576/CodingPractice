package org.pk.practices.cabreservation.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.pk.practices.cabreservation.auth.AccountType;
import org.pk.practices.cabreservation.auth.AuthenticatedAccount;
import org.pk.practices.cabreservation.common.AuthorizationException;
import org.pk.practices.cabreservation.driver.DriverRepository;
import org.pk.practices.cabreservation.rider.Rider;
import org.pk.practices.cabreservation.rider.RiderRepository;
import org.pk.practices.cabreservation.trip.CancelledBy;
import org.pk.practices.cabreservation.trip.Trip;
import org.pk.practices.cabreservation.trip.TripRequest;
import org.pk.practices.cabreservation.trip.TripService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every trip returned here is a {@link TripView}, not the raw {@link Trip}
 * — "rider sees driver details, driver sees rider details" means the
 * counterpart's name/rating has to actually be in the response, not just a
 * bare ID the client can't resolve on its own.
 */
public class TripController {

    private final TripService tripService;
    private final RiderRepository riderRepository;
    private final DriverRepository driverRepository;

    public TripController(TripService tripService, RiderRepository riderRepository, DriverRepository driverRepository) {
        this.tripService = tripService;
        this.riderRepository = riderRepository;
        this.driverRepository = driverRepository;
    }

    public void register(Javalin app) {
        app.post("/v1/trips", this::create);
        app.get("/v1/trips", this::list);
        app.get("/v1/trips/{id}", this::get);
        app.post("/v1/trips/{id}/arrived", this::arrived);
        app.post("/v1/trips/{id}/start", this::start);
        app.post("/v1/trips/{id}/complete", this::complete);
        app.post("/v1/trips/{id}/cancel", this::cancel);
    }

    private void create(Context ctx) {
        TripRequest request = ctx.bodyAsClass(TripRequest.class);
        Trip trip = tripService.requestTrip(riderId(ctx), request);
        ctx.status(201).json(enrich(trip));
    }

    /** "Rider sees past rides" / "driver sees past trips" — scoped to whichever role the caller's account actually is. */
    private void list(Context ctx) {
        AuthenticatedAccount account = account(ctx);
        List<Trip> trips = account.accountType() == AccountType.RIDER
                ? tripService.listForRider(account.accountId())
                : tripService.listForDriver(account.accountId());
        ctx.json(enrichAll(trips));
    }

    private void get(Context ctx) {
        tripService.get(ctx.pathParam("id"))
                .map(trip -> { requireParty(ctx, trip); return trip; })
                .map(this::enrich)
                .ifPresentOrElse(ctx::json, () -> ctx.status(404).json(Map.of("error", "NOT_FOUND")));
    }

    private void arrived(Context ctx) {
        Trip trip = requireOwnedByAssignedDriver(ctx);
        ctx.json(enrich(tripService.markArrived(trip.tripId())));
    }

    private void start(Context ctx) {
        Trip trip = requireOwnedByAssignedDriver(ctx);
        ctx.json(enrich(tripService.markStarted(trip.tripId())));
    }

    private void complete(Context ctx) {
        Trip trip = requireOwnedByAssignedDriver(ctx);
        ctx.json(enrich(tripService.markCompleted(trip.tripId())));
    }

    private void cancel(Context ctx) {
        Trip trip = tripService.get(ctx.pathParam("id"))
                .orElseThrow(() -> new io.javalin.http.NotFoundResponse());
        requireParty(ctx, trip);
        AuthenticatedAccount account = account(ctx);
        CancelledBy actor = account.accountType() == AccountType.RIDER ? CancelledBy.RIDER : CancelledBy.DRIVER;
        ctx.json(enrich(tripService.cancel(trip.tripId(), actor)));
    }

    private String riderId(Context ctx) {
        AuthenticatedAccount account = account(ctx);
        if (account.accountType() != AccountType.RIDER) {
            throw new AuthorizationException("This endpoint requires a rider account");
        }
        return account.accountId();
    }

    /** The caller must be either the trip's rider or its assigned driver — not an arbitrary authenticated account. */
    private void requireParty(Context ctx, Trip trip) {
        AuthenticatedAccount account = account(ctx);
        boolean isRider = account.accountType() == AccountType.RIDER && account.accountId().equals(trip.riderId());
        boolean isDriver = account.accountType() == AccountType.DRIVER && account.accountId().equals(trip.driverId());
        if (!isRider && !isDriver) {
            throw new AuthorizationException("You are not a party to this trip");
        }
    }

    /** Arrived/start/complete are driver actions on a driver's *own* assigned trip — a different driver's token must not be able to call these. */
    private Trip requireOwnedByAssignedDriver(Context ctx) {
        AuthenticatedAccount account = account(ctx);
        if (account.accountType() != AccountType.DRIVER) {
            throw new AuthorizationException("This endpoint requires a driver account");
        }
        Trip trip = tripService.get(ctx.pathParam("id"))
                .orElseThrow(() -> new io.javalin.http.NotFoundResponse());
        if (!account.accountId().equals(trip.driverId())) {
            throw new AuthorizationException("You are not the driver assigned to this trip");
        }
        return trip;
    }

    private AuthenticatedAccount account(Context ctx) {
        return ctx.attribute(AuthController.ACCOUNT_ATTRIBUTE);
    }

    private TripView enrich(Trip trip) {
        PartyInfo rider = riderRepository.findById(trip.riderId()).map(TripController::toPartyInfo).orElse(null);
        PartyInfo driver = trip.driverId() == null ? null : toDriverPartyInfo(trip.driverId());
        PartyInfo offeredDriver = trip.offeredDriverId() == null ? null : toDriverPartyInfo(trip.offeredDriverId());
        return TripView.of(trip, rider, driver, offeredDriver);
    }

    /** Memoizes name/rating/vehicle lookups per rider/driver ID — a history page commonly has several trips with the same counterpart. */
    private List<TripView> enrichAll(List<Trip> trips) {
        Map<String, PartyInfo> riders = new HashMap<>();
        Map<String, PartyInfo> drivers = new HashMap<>();
        return trips.stream()
                .map(trip -> {
                    PartyInfo rider = riders.computeIfAbsent(trip.riderId(),
                            id -> riderRepository.findById(id).map(TripController::toPartyInfo).orElse(null));
                    PartyInfo driver = trip.driverId() == null ? null
                            : drivers.computeIfAbsent(trip.driverId(), this::toDriverPartyInfo);
                    PartyInfo offeredDriver = trip.offeredDriverId() == null ? null
                            : drivers.computeIfAbsent(trip.offeredDriverId(), this::toDriverPartyInfo);
                    return TripView.of(trip, rider, driver, offeredDriver);
                })
                .toList();
    }

    /** A rider needs the cab's plate/make/model to spot their ride, not just the driver's name — so vehicle comes along with every driver PartyInfo. */
    private PartyInfo toDriverPartyInfo(String driverId) {
        return driverRepository.findById(driverId).map(driver -> {
            VehicleInfo vehicle = driverRepository.findVehicleByDriverId(driverId).map(VehicleInfo::of).orElse(null);
            return new PartyInfo(driver.driverId(), driver.name(), driver.rating(), vehicle);
        }).orElse(null);
    }

    private static PartyInfo toPartyInfo(Rider rider) {
        return new PartyInfo(rider.riderId(), rider.name(), rider.rating(), null);
    }
}

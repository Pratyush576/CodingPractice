package org.pk.practices.supplychain.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.pk.practices.supplychain.auth.AuthenticatedParty;
import org.pk.practices.supplychain.booking.Booking;
import org.pk.practices.supplychain.booking.BookingAmendment;
import org.pk.practices.supplychain.booking.BookingService;
import org.pk.practices.supplychain.booking.CreateBookingRequest;
import org.pk.practices.supplychain.booking.ReserveCapacityRequest;
import org.pk.practices.supplychain.common.ConflictException;
import org.pk.practices.supplychain.common.DomainException;
import org.pk.practices.supplychain.common.ValidationException;
import org.pk.practices.supplychain.party.PartyRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST surface per LLD.md §2 "Communication & storage" — Exposed via.
 * {@link AuthController#requireSession} runs first on every route here and
 * populates {@code ctx.attribute(ACTOR_ATTRIBUTE)}; nothing in this class
 * trusts a client-supplied tenant/shipper ID.
 */
public class BookingController {

    private final BookingService bookingService;
    private final PartyRepository partyRepository;

    public BookingController(BookingService bookingService, PartyRepository partyRepository) {
        this.bookingService = bookingService;
        this.partyRepository = partyRepository;
    }

    public void register(Javalin app) {
        app.post("/v1/bookings", this::createDraft);
        app.get("/v1/bookings", this::list);
        app.put("/v1/bookings/{id}/submit", this::submit);
        app.put("/v1/bookings/{id}/amend", this::amend);
        app.delete("/v1/bookings/{id}", this::cancel);
        app.get("/v1/bookings/{id}", this::get);
        app.get("/v1/bookings/{id}/candidates", this::findCandidates);
        app.post("/v1/bookings/{id}/reserve", this::reserveCapacity);

        app.exception(ValidationException.class, (e, ctx) ->
                ctx.status(400).json(Map.of("error", "VALIDATION_FAILED", "violations", e.violations())));
        app.exception(ConflictException.class, (e, ctx) ->
                ctx.status(409).json(Map.of("error", "CONFLICT", "message", e.getMessage())));
        app.exception(DomainException.class, (e, ctx) ->
                ctx.status("NOT_FOUND".equals(e.type()) ? 404 : 422)
                        .json(Map.of("error", e.type(), "message", e.getMessage())));
    }

    private void createDraft(Context ctx) {
        CreateBookingRequest request = ctx.bodyAsClass(CreateBookingRequest.class);
        Booking booking = bookingService.createDraft(actor(ctx), request);
        ctx.status(201).json(enrich(booking));
    }

    private void list(Context ctx) {
        List<Booking> bookings = bookingService.list(actor(ctx), ctx.queryParam("status"));
        ctx.json(enrichAll(bookings));
    }

    private void submit(Context ctx) {
        Booking booking = bookingService.submit(actor(ctx), ctx.pathParam("id"));
        ctx.json(enrich(booking));
    }

    private void amend(Context ctx) {
        BookingAmendment amendment = ctx.bodyAsClass(BookingAmendment.class);
        Booking booking = bookingService.amend(actor(ctx), ctx.pathParam("id"), amendment);
        ctx.json(enrich(booking));
    }

    private void cancel(Context ctx) {
        String reason = ctx.queryParam("reason");
        Booking booking = bookingService.cancel(actor(ctx), ctx.pathParam("id"), reason);
        ctx.json(enrich(booking));
    }

    private void get(Context ctx) {
        bookingService.get(actor(ctx), ctx.pathParam("id"))
                .map(this::enrich)
                .ifPresentOrElse(ctx::json, () -> ctx.status(404).json(Map.of("error", "NOT_FOUND")));
    }

    private void findCandidates(Context ctx) {
        ctx.json(bookingService.findCandidates(actor(ctx), ctx.pathParam("id")));
    }

    private void reserveCapacity(Context ctx) {
        ReserveCapacityRequest request = ctx.bodyAsClass(ReserveCapacityRequest.class);
        Booking booking = bookingService.reserveCapacity(actor(ctx), ctx.pathParam("id"), request.offeringId());
        ctx.json(enrich(booking));
    }

    private AuthenticatedParty actor(Context ctx) {
        // Always non-null here — AuthController.requireSession runs as a `before` filter
        // on every route in this class and throws AuthenticationException first if it isn't.
        return ctx.attribute(AuthController.ACTOR_ATTRIBUTE);
    }

    private BookingResponse enrich(Booking booking) {
        String shipperName = partyRepository.findById(booking.shipperId())
                .map(party -> party.name())
                .orElse(null);
        return new BookingResponse(booking, shipperName);
    }

    /** Memoizes the name lookup per shipperId — a list commonly has several bookings from the same Shipper. */
    private List<BookingResponse> enrichAll(List<Booking> bookings) {
        Map<String, String> namesByShipperId = new HashMap<>();
        return bookings.stream()
                .map(booking -> new BookingResponse(booking, namesByShipperId.computeIfAbsent(booking.shipperId(),
                        id -> partyRepository.findById(id).map(party -> party.name()).orElse(null))))
                .toList();
    }
}

package org.pk.practices.cabreservation.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.pk.practices.cabreservation.admin.AdminStatsRepository;
import org.pk.practices.cabreservation.admin.AdminTripRepository;
import org.pk.practices.cabreservation.common.AuthenticationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Platform-wide stats and per-request detail (DESIGN.md §7) — deliberately
 * not gated by the rider/driver {@code SessionManager}, since an admin
 * isn't a rider or driver account at all. A shared-secret header is the
 * whole auth model here, same "not a production security model" spirit as
 * the PBKDF2 + bearer-token layer elsewhere in this module (see
 * cabreservation/README.md).
 */
public class AdminController {

    private static final String TOKEN_HEADER = "X-Admin-Token";

    private final String adminToken;
    private final AdminStatsRepository statsRepository;
    private final AdminTripRepository tripRepository;

    public AdminController(String adminToken, AdminStatsRepository statsRepository, AdminTripRepository tripRepository) {
        this.adminToken = adminToken;
        this.statsRepository = statsRepository;
        this.tripRepository = tripRepository;
    }

    public void register(Javalin app) {
        app.get("/v1/admin/stats", this::stats);
        app.get("/v1/admin/trips", this::trips);
    }

    private void stats(Context ctx) {
        requireAdmin(ctx);
        ctx.json(statsRepository.compute(windowStart(ctx.queryParam("window"))));
    }

    /** Every request platform-wide within the window, financial data included — status/text filtering happens client-side, same as everywhere else in this module. */
    private void trips(Context ctx) {
        requireAdmin(ctx);
        ctx.json(tripRepository.list(windowStart(ctx.queryParam("window"))));
    }

    private void requireAdmin(Context ctx) {
        String presented = ctx.header(TOKEN_HEADER);
        if (presented == null || !presented.equals(adminToken)) {
            throw new AuthenticationException("Missing or invalid " + TOKEN_HEADER + " header");
        }
    }

    /** Rolling windows (last 24h/7d/30d), not calendar boundaries — same choice as the rider/driver earnings panels, for the same reason: no server-side notion of the caller's timezone to anchor a calendar day to. */
    private static Instant windowStart(String window) {
        Instant now = Instant.now();
        if ("today".equals(window)) return now.minus(1, ChronoUnit.DAYS);
        if ("week".equals(window)) return now.minus(7, ChronoUnit.DAYS);
        if ("month".equals(window)) return now.minus(30, ChronoUnit.DAYS);
        return Instant.EPOCH; // "all" or unrecognized — no lower bound
    }
}

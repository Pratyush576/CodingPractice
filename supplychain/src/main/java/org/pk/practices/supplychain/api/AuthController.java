package org.pk.practices.supplychain.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.pk.practices.supplychain.auth.AuthService;
import org.pk.practices.supplychain.auth.AuthenticatedParty;
import org.pk.practices.supplychain.auth.LoginRequest;
import org.pk.practices.supplychain.auth.LoginResult;
import org.pk.practices.supplychain.auth.RegisterOutcome;
import org.pk.practices.supplychain.auth.RegisterRequest;
import org.pk.practices.supplychain.auth.SessionManager;
import org.pk.practices.supplychain.common.AuthenticationException;
import org.pk.practices.supplychain.common.AuthorizationException;
import org.pk.practices.supplychain.party.PartyRepository;

import java.util.Map;

/**
 * {@code POST /v1/auth/register}, {@code /login}, {@code /logout}, plus the
 * Bearer-token gate every {@code /v1/bookings/*} request has to pass first.
 * ValidationException/ConflictException are handled globally by
 * {@link BookingController} — exception mappers are per-app, not per-controller,
 * so registering them twice would be redundant.
 */
public class AuthController {

    public static final String ACTOR_ATTRIBUTE = "actor";

    private final AuthService authService;
    private final SessionManager sessionManager;
    private final PartyRepository partyRepository;

    public AuthController(AuthService authService, SessionManager sessionManager, PartyRepository partyRepository) {
        this.authService = authService;
        this.sessionManager = sessionManager;
        this.partyRepository = partyRepository;
    }

    public void register(Javalin app) {
        app.post("/v1/auth/register", this::handleRegister);
        app.post("/v1/auth/login", this::handleLogin);
        app.post("/v1/auth/logout", this::handleLogout);
        // Deliberately public — no session exists yet at registration time. Just tenant ID
        // strings, nothing about the parties within them, so low sensitivity to expose.
        app.get("/v1/tenants", this::listTenants);

        // Resolved once here so every downstream handler in this module can just read
        // ctx.attribute(ACTOR_ATTRIBUTE) instead of re-checking a token itself. Every
        // protected path prefix is registered here, not scattered across controllers.
        app.before("/v1/bookings", this::requireSession);
        app.before("/v1/bookings/*", this::requireSession);
        app.before("/v1/capacity-offerings", this::requireSession);
        app.before("/v1/capacity-offerings/*", this::requireSession);

        app.exception(AuthenticationException.class, (e, ctx) ->
                ctx.status(401).json(Map.of("error", "UNAUTHENTICATED", "message", e.getMessage())));
        app.exception(AuthorizationException.class, (e, ctx) ->
                ctx.status(403).json(Map.of("error", "FORBIDDEN", "message", e.getMessage())));
    }

    private void requireSession(Context ctx) {
        AuthenticatedParty actor = sessionManager.resolve(bearerToken(ctx))
                .orElseThrow(() -> new AuthenticationException("Missing or expired session — log in again"));
        ctx.attribute(ACTOR_ATTRIBUTE, actor);
    }

    private void handleRegister(Context ctx) {
        RegisterOutcome outcome = authService.register(ctx.bodyAsClass(RegisterRequest.class));
        switch (outcome) {
            case RegisterOutcome.Registered registered -> ctx.status(201).json(registered.result());
            case RegisterOutcome.NewTenantConfirmationRequired pending -> ctx.status(409).json(Map.of(
                    "error", "NEW_TENANT_CONFIRMATION_REQUIRED",
                    "tenantId", pending.tenantId(),
                    "message", "No existing account uses tenant '" + pending.tenantId() + "' — this would create "
                            + "a brand-new tenant. Resubmit with confirmNewTenant: true to proceed, or fix the "
                            + "tenant ID if you meant to join an existing one."
            ));
        }
    }

    private void handleLogin(Context ctx) {
        LoginResult result = authService.login(ctx.bodyAsClass(LoginRequest.class));
        ctx.json(result);
    }

    private void listTenants(Context ctx) {
        ctx.json(partyRepository.listDistinctTenantIds());
    }

    private void handleLogout(Context ctx) {
        String token = bearerToken(ctx);
        if (token != null) {
            authService.logout(token);
        }
        ctx.status(204);
    }

    static String bearerToken(Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring("Bearer ".length()).trim();
    }
}

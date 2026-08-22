package org.pk.practices.servicesmarketplace.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.pk.practices.servicesmarketplace.auth.AuthService;
import org.pk.practices.servicesmarketplace.auth.AuthenticatedAccount;
import org.pk.practices.servicesmarketplace.auth.LoginRequest;
import org.pk.practices.servicesmarketplace.auth.LoginResult;
import org.pk.practices.servicesmarketplace.auth.RegisterCustomerRequest;
import org.pk.practices.servicesmarketplace.auth.RegisterProRequest;
import org.pk.practices.servicesmarketplace.auth.SessionManager;
import org.pk.practices.servicesmarketplace.common.AuthenticationException;
import org.pk.practices.servicesmarketplace.common.AuthorizationException;
import org.pk.practices.servicesmarketplace.common.ConflictException;
import org.pk.practices.servicesmarketplace.common.DomainException;
import org.pk.practices.servicesmarketplace.common.ValidationException;

import java.util.Map;

/**
 * {@code POST /v1/auth/register/{customer,pro}}, {@code /login}, {@code
 * /logout}, plus the Bearer-token gate every protected route has to pass
 * first. Also registers the exception→HTTP mappers shared by every
 * controller in this module, since Javalin's exception mappers are
 * per-app, not per-controller.
 */
public class AuthController {

    public static final String ACCOUNT_ATTRIBUTE = "account";

    private final AuthService authService;
    private final SessionManager sessionManager;

    public AuthController(AuthService authService, SessionManager sessionManager) {
        this.authService = authService;
        this.sessionManager = sessionManager;
    }

    public void register(Javalin app) {
        app.post("/v1/auth/register/customer", this::handleRegisterCustomer);
        app.post("/v1/auth/register/pro", this::handleRegisterPro);
        app.post("/v1/auth/login", this::handleLogin);
        app.post("/v1/auth/logout", this::handleLogout);

        app.before("/v1/requests", this::requireSession);
        app.before("/v1/requests/*", this::requireSession);
        app.before("/v1/leads", this::requireSession);
        app.before("/v1/leads/*", this::requireSession);
        app.before("/v1/credits", this::requireSession);
        app.before("/v1/credits/*", this::requireSession);
        app.before("/v1/pros/me", this::requireSession);
        app.before("/v1/pros/me/*", this::requireSession);

        app.exception(AuthenticationException.class, (e, ctx) ->
                ctx.status(401).json(Map.of("error", "UNAUTHENTICATED", "message", e.getMessage())));
        app.exception(AuthorizationException.class, (e, ctx) ->
                ctx.status(403).json(Map.of("error", "FORBIDDEN", "message", e.getMessage())));
        app.exception(ValidationException.class, (e, ctx) ->
                ctx.status(400).json(Map.of("error", "VALIDATION_FAILED", "violations", e.violations())));
        app.exception(ConflictException.class, (e, ctx) ->
                ctx.status(409).json(Map.of("error", "CONFLICT", "message", e.getMessage())));
        app.exception(DomainException.class, (e, ctx) ->
                ctx.status("NOT_FOUND".equals(e.type()) ? 404 : 422)
                        .json(Map.of("error", e.type(), "message", e.getMessage())));
    }

    private void requireSession(Context ctx) {
        AuthenticatedAccount account = sessionManager.resolve(bearerToken(ctx))
                .orElseThrow(() -> new AuthenticationException("Missing or expired session — log in again"));
        ctx.attribute(ACCOUNT_ATTRIBUTE, account);
    }

    private void handleRegisterCustomer(Context ctx) {
        LoginResult result = authService.registerCustomer(ctx.bodyAsClass(RegisterCustomerRequest.class));
        ctx.status(201).json(result);
    }

    private void handleRegisterPro(Context ctx) {
        LoginResult result = authService.registerPro(ctx.bodyAsClass(RegisterProRequest.class));
        ctx.status(201).json(result);
    }

    private void handleLogin(Context ctx) {
        ctx.json(authService.login(ctx.bodyAsClass(LoginRequest.class)));
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

    public static AuthenticatedAccount account(Context ctx) {
        return ctx.attribute(ACCOUNT_ATTRIBUTE);
    }
}

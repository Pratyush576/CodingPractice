package org.pk.practices.cabreservation.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.pk.practices.cabreservation.auth.AuthService;
import org.pk.practices.cabreservation.auth.AuthenticatedAccount;
import org.pk.practices.cabreservation.auth.LoginRequest;
import org.pk.practices.cabreservation.auth.LoginResult;
import org.pk.practices.cabreservation.auth.RegisterDriverRequest;
import org.pk.practices.cabreservation.auth.RegisterRiderRequest;
import org.pk.practices.cabreservation.auth.SessionManager;
import org.pk.practices.cabreservation.common.AuthenticationException;
import org.pk.practices.cabreservation.common.AuthorizationException;
import org.pk.practices.cabreservation.common.ConflictException;
import org.pk.practices.cabreservation.common.DomainException;
import org.pk.practices.cabreservation.common.ValidationException;

import java.util.Map;

/**
 * {@code POST /v1/auth/register/{rider,driver}}, {@code /login}, {@code
 * /logout}, plus the Bearer-token gate every protected route has to pass
 * first. Also registers the exception→HTTP mappers shared by every
 * controller in this module (ValidationException/ConflictException/
 * DomainException), since Javalin's exception mappers are per-app, not
 * per-controller — registering them here once avoids each controller
 * redeclaring the same three.
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
        app.post("/v1/auth/register/rider", this::handleRegisterRider);
        app.post("/v1/auth/register/driver", this::handleRegisterDriver);
        app.post("/v1/auth/login", this::handleLogin);
        app.post("/v1/auth/logout", this::handleLogout);

        // Resolved once here so every downstream handler can just read
        // ctx.attribute(ACCOUNT_ATTRIBUTE) instead of re-checking a token itself.
        app.before("/v1/trips", this::requireSession);
        app.before("/v1/trips/*", this::requireSession);
        app.before("/v1/drivers", this::requireSession);
        app.before("/v1/drivers/*", this::requireSession);

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

    private void handleRegisterRider(Context ctx) {
        LoginResult result = authService.registerRider(ctx.bodyAsClass(RegisterRiderRequest.class));
        ctx.status(201).json(result);
    }

    private void handleRegisterDriver(Context ctx) {
        LoginResult result = authService.registerDriver(ctx.bodyAsClass(RegisterDriverRequest.class));
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
}

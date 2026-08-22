package org.pk.practices.servicesmarketplace.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import org.pk.practices.servicesmarketplace.auth.AccountType;
import org.pk.practices.servicesmarketplace.auth.AuthenticatedAccount;
import org.pk.practices.servicesmarketplace.auth.ProProfileRequest;
import org.pk.practices.servicesmarketplace.common.AuthorizationException;
import org.pk.practices.servicesmarketplace.common.ValidationException;
import org.pk.practices.servicesmarketplace.pro.ProProfile;
import org.pk.practices.servicesmarketplace.pro.ProRepository;

import java.time.Instant;
import java.util.List;

public class ProProfileController {

    private final ProRepository proRepository;

    public ProProfileController(ProRepository proRepository) {
        this.proRepository = proRepository;
    }

    public void register(Javalin app) {
        app.get("/v1/pros/me/profile", this::get);
        app.put("/v1/pros/me/profile", this::update);
    }

    private void get(Context ctx) {
        ctx.json(proRepository.findProfileByProId(proId(ctx)).orElseThrow(NotFoundResponse::new));
    }

    private void update(Context ctx) {
        ProProfileRequest body = ctx.bodyAsClass(ProProfileRequest.class);
        List<String> violations = new java.util.ArrayList<>();
        if (body.categoryId() == null || body.categoryId().isBlank()) violations.add("categoryId is required");
        if (body.lat() == null) violations.add("lat is required");
        if (body.lng() == null) violations.add("lng is required");
        if (body.radiusKm() == null) violations.add("radiusKm is required");
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        String proId = proId(ctx);
        ProProfile existing = proRepository.findProfileByProId(proId).orElseThrow(NotFoundResponse::new);
        ProProfile updated = new ProProfile(proId, body.categoryId(), body.lat(), body.lng(), body.radiusKm(),
                body.startingPrice(), existing.minBudget(), existing.maxJobSize(), Instant.now());
        proRepository.updateProfile(updated);
        ctx.json(updated);
    }

    private String proId(Context ctx) {
        AuthenticatedAccount account = AuthController.account(ctx);
        if (account.accountType() != AccountType.PRO) {
            throw new AuthorizationException("This endpoint requires a pro account");
        }
        return account.accountId();
    }
}

package org.pk.practices.servicesmarketplace.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import org.pk.practices.servicesmarketplace.auth.AccountType;
import org.pk.practices.servicesmarketplace.auth.AuthenticatedAccount;
import org.pk.practices.servicesmarketplace.common.AuthorizationException;
import org.pk.practices.servicesmarketplace.common.ValidationException;
import org.pk.practices.servicesmarketplace.quote.QuoteMessagingService;
import org.pk.practices.servicesmarketplace.request.Request;
import org.pk.practices.servicesmarketplace.request.RequestService;

import java.util.ArrayList;
import java.util.List;

public class RequestController {

    private final RequestService requestService;
    private final QuoteMessagingService quoteMessagingService;

    public RequestController(RequestService requestService, QuoteMessagingService quoteMessagingService) {
        this.requestService = requestService;
        this.quoteMessagingService = quoteMessagingService;
    }

    public void register(Javalin app) {
        app.post("/v1/requests", this::create);
        app.get("/v1/requests/mine", this::mine);
        app.get("/v1/requests/{id}", this::get);
        app.get("/v1/requests/{id}/quotes", this::quotes);
        app.post("/v1/requests/{id}/hire", this::hire);
    }

    private void create(Context ctx) {
        PostRequestRequest body = ctx.bodyAsClass(PostRequestRequest.class);
        List<String> violations = new ArrayList<>();
        if (body.categoryId() == null || body.categoryId().isBlank()) violations.add("categoryId is required");
        if (body.answers() == null) violations.add("answers is required");
        if (body.lat() == null) violations.add("lat is required");
        if (body.lng() == null) violations.add("lng is required");
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        Request request = requestService.postRequest(customerId(ctx), body.categoryId(), body.answers().toString(),
                body.lat(), body.lng(), body.desiredTiming());
        ctx.status(201).json(request);
    }

    private void mine(Context ctx) {
        ctx.json(requestService.listForCustomer(customerId(ctx)));
    }

    private void get(Context ctx) {
        Request request = requireOwned(ctx);
        ctx.json(request);
    }

    private void quotes(Context ctx) {
        Request request = requireOwned(ctx);
        ctx.json(quoteMessagingService.listForRequest(request.requestId()));
    }

    private void hire(Context ctx) {
        Request request = requireOwned(ctx);
        HireRequest body = ctx.bodyAsClass(HireRequest.class);
        if (body.quoteId() == null || body.quoteId().isBlank()) {
            throw new ValidationException(List.of("quoteId is required"));
        }
        ctx.json(requestService.hire(request.requestId(), body.quoteId(), customerId(ctx)));
    }

    private String customerId(Context ctx) {
        AuthenticatedAccount account = AuthController.account(ctx);
        if (account.accountType() != AccountType.CUSTOMER) {
            throw new AuthorizationException("This endpoint requires a customer account");
        }
        return account.accountId();
    }

    private Request requireOwned(Context ctx) {
        Request request = requestService.get(ctx.pathParam("id")).orElseThrow(NotFoundResponse::new);
        if (!request.customerId().equals(customerId(ctx))) {
            throw new AuthorizationException("This request does not belong to you");
        }
        return request;
    }
}

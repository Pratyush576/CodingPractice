package org.pk.practices.servicesmarketplace.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.pk.practices.servicesmarketplace.auth.AccountType;
import org.pk.practices.servicesmarketplace.auth.AuthenticatedAccount;
import org.pk.practices.servicesmarketplace.common.AuthorizationException;
import org.pk.practices.servicesmarketplace.common.ValidationException;
import org.pk.practices.servicesmarketplace.lead.LeadService;
import org.pk.practices.servicesmarketplace.quote.QuoteMessagingService;

import java.util.List;

public class LeadController {

    private final LeadService leadService;
    private final QuoteMessagingService quoteMessagingService;

    public LeadController(LeadService leadService, QuoteMessagingService quoteMessagingService) {
        this.leadService = leadService;
        this.quoteMessagingService = quoteMessagingService;
    }

    public void register(Javalin app) {
        app.get("/v1/leads/mine", this::mine);
        app.post("/v1/leads/{id}/unlock", this::unlock);
        app.post("/v1/leads/{id}/quote", this::quote);
    }

    private void mine(Context ctx) {
        ctx.json(leadService.listForPro(proId(ctx)));
    }

    private void unlock(Context ctx) {
        ctx.json(leadService.unlock(ctx.pathParam("id"), proId(ctx)));
    }

    private void quote(Context ctx) {
        String proId = proId(ctx);
        // Ownership of the lead itself is re-checked inside sendQuote — proId here is only used to
        // authorize the *caller*, not assumed to already own the path's lead id.
        leadService.get(ctx.pathParam("id"), proId);
        SendQuoteRequest body = ctx.bodyAsClass(SendQuoteRequest.class);
        if (body.price() == null || body.price() <= 0) {
            throw new ValidationException(List.of("price must be positive"));
        }
        ctx.status(201).json(quoteMessagingService.sendQuote(ctx.pathParam("id"), proId, body.price(), body.message()));
    }

    private String proId(Context ctx) {
        AuthenticatedAccount account = AuthController.account(ctx);
        if (account.accountType() != AccountType.PRO) {
            throw new AuthorizationException("This endpoint requires a pro account");
        }
        return account.accountId();
    }
}

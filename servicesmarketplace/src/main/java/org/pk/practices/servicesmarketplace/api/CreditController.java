package org.pk.practices.servicesmarketplace.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.pk.practices.servicesmarketplace.auth.AccountType;
import org.pk.practices.servicesmarketplace.auth.AuthenticatedAccount;
import org.pk.practices.servicesmarketplace.common.AuthorizationException;
import org.pk.practices.servicesmarketplace.common.ValidationException;
import org.pk.practices.servicesmarketplace.credit.CreditLedgerService;

import java.util.List;
import java.util.Map;

public class CreditController {

    private final CreditLedgerService creditLedgerService;

    public CreditController(CreditLedgerService creditLedgerService) {
        this.creditLedgerService = creditLedgerService;
    }

    public void register(Javalin app) {
        app.get("/v1/credits/balance", this::balance);
        app.post("/v1/credits/purchase", this::purchase);
    }

    private void balance(Context ctx) {
        ctx.json(Map.of("balance", creditLedgerService.getBalance(proId(ctx))));
    }

    /** No real payment gateway in Phase 1 (DESIGN.md Phase 2 introduces PaymentGateway/PayoutProvider for Instant Book) — a directly-authenticated endpoint. */
    private void purchase(Context ctx) {
        PurchaseCreditsRequest body = ctx.bodyAsClass(PurchaseCreditsRequest.class);
        if (body.amount() == null || body.amount() <= 0) {
            throw new ValidationException(List.of("amount must be positive"));
        }
        String proId = proId(ctx);
        creditLedgerService.purchaseCredits(proId, body.amount());
        ctx.json(Map.of("balance", creditLedgerService.getBalance(proId)));
    }

    private String proId(Context ctx) {
        AuthenticatedAccount account = AuthController.account(ctx);
        if (account.accountType() != AccountType.PRO) {
            throw new AuthorizationException("This endpoint requires a pro account");
        }
        return account.accountId();
    }
}

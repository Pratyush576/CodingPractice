package org.pk.practices.supplychain.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.pk.practices.supplychain.auth.AuthenticatedParty;
import org.pk.practices.supplychain.matching.CapacityOffering;
import org.pk.practices.supplychain.matching.CapacityOfferingService;
import org.pk.practices.supplychain.matching.CreateCapacityOfferingRequest;

/** REST surface for the Operator-only Supply side — LLD.md §4's "Depends on: CapacityOfferingRepository." */
public class CapacityOfferingController {

    private final CapacityOfferingService capacityOfferingService;

    public CapacityOfferingController(CapacityOfferingService capacityOfferingService) {
        this.capacityOfferingService = capacityOfferingService;
    }

    public void register(Javalin app) {
        app.post("/v1/capacity-offerings", this::create);
        app.get("/v1/capacity-offerings", this::list);
        // Auth gating for this path is registered in AuthController, alongside /v1/bookings*.
    }

    private void create(Context ctx) {
        CreateCapacityOfferingRequest request = ctx.bodyAsClass(CreateCapacityOfferingRequest.class);
        CapacityOffering offering = capacityOfferingService.create(actor(ctx), request);
        ctx.status(201).json(offering);
    }

    private void list(Context ctx) {
        ctx.json(capacityOfferingService.listOwn(actor(ctx)));
    }

    private AuthenticatedParty actor(Context ctx) {
        return ctx.attribute(AuthController.ACTOR_ATTRIBUTE);
    }
}

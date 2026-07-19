package org.pk.practices.design.api.rest;

import io.javalin.Javalin;
import io.javalin.http.HttpResponseException;
import org.pk.practices.design.api.rest.handler.EmployeeHandler;
import org.pk.practices.design.api.rest.model.ErrorResponse;
import org.pk.practices.design.api.rest.store.EmployeeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the Employee REST API.
 *
 * <p>This class is responsible for three things:
 * <ol>
 *   <li><b>Wiring</b> — construct the store and handler, inject the store into the handler.</li>
 *   <li><b>Routing</b> — declare which HTTP method + path maps to which handler method.</li>
 *   <li><b>Cross-cutting concerns</b> — request logging, exception-to-HTTP-status mapping,
 *       and graceful shutdown. These do not belong in handlers.</li>
 * </ol>
 *
 * <h2>Framework: Javalin</h2>
 * Javalin is a lightweight Java/Kotlin web framework that embeds Jetty as its HTTP
 * server. It is a good learning target because:
 * <ul>
 *   <li>There is no magic: everything is explicit — routes, middleware, exception handlers.</li>
 *   <li>The same patterns (routing, context, exception mapping) appear in all REST
 *       frameworks (Spring MVC, JAX-RS, etc.), so concepts transfer directly.</li>
 * </ul>
 *
 * <h2>Port</h2>
 * The server runs on {@value #PORT} to avoid conflicting with the gRPC server on 8080.
 */
public class RestApiServer {

    private static final Logger log = LoggerFactory.getLogger(RestApiServer.class);
    static final int PORT = 8081;

    public static void main(String[] args) {

        // --- Dependency wiring -------------------------------------------------------
        // In production this would be handled by a DI framework (Spring, Guice, etc.).
        // For a hands-on, manual wiring makes the dependency graph visible.
        EmployeeStore store = new EmployeeStore();
        EmployeeHandler handler = new EmployeeHandler(store);

        // --- Server configuration ----------------------------------------------------
        Javalin app = Javalin.create(config -> {
            // Log every request: method, path, response status, and elapsed time.
            // This is the minimal observability every production API needs.
            config.requestLogger.http((ctx, ms) ->
                    log.info("{} {} -> {} ({}ms)",
                            ctx.method(), ctx.path(), ctx.status(), ms.intValue()));
        });

        // --- Routes ------------------------------------------------------------------
        // Each line reads as: "HTTP verb + URL pattern → handler method".
        // {id} is a path parameter extracted via ctx.pathParam("id") in the handler.
        app.get(   "/api/employees",       handler::getAll);
        app.get(   "/api/employees/{id}",  handler::getById);
        app.post(  "/api/employees",       handler::create);
        app.put(   "/api/employees/{id}",  handler::update);
        app.delete("/api/employees/{id}",  handler::delete);

        // Health check — a lightweight endpoint that load balancers and monitoring
        // systems probe to decide whether the instance is alive.
        app.get("/health", ctx -> ctx.result("OK"));

        // --- Exception handlers ------------------------------------------------------
        // Javalin HTTP exceptions (NotFoundResponse, BadRequestResponse, etc.) carry a
        // status code. This single handler converts them all into a JSON ErrorResponse
        // body, so every non-2xx response has the same shape.
        app.exception(HttpResponseException.class, (e, ctx) ->
                ctx.status(e.getStatus()).json(new ErrorResponse(e.getMessage())));

        // Catch-all for unexpected runtime errors. Never expose raw stack traces to
        // callers — log the detail server-side and return a generic 500 message.
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception on {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).json(new ErrorResponse("Internal server error"));
        });

        // --- Start -------------------------------------------------------------------
        app.start(PORT);
        log.info("REST API listening on http://localhost:{}", PORT);

        // --- Graceful shutdown -------------------------------------------------------
        // When the JVM receives SIGTERM (e.g. from Ctrl+C or a container orchestrator),
        // this hook lets in-flight requests complete before the process exits.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — stopping server");
            app.stop();
        }));
    }
}

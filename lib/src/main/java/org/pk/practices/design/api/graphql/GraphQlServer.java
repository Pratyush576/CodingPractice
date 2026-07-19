package org.pk.practices.design.api.graphql;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import io.javalin.Javalin;
import org.pk.practices.design.api.graphql.schema.SchemaBuilder;
import org.pk.practices.design.api.graphql.store.EmployeeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Entry point for the GraphQL API server.
 *
 * <p>GraphQL is transport-agnostic — the graphql-java engine knows nothing about HTTP.
 * This class bridges HTTP and GraphQL:
 * <ol>
 *   <li>Exposes a single {@code POST /graphql} endpoint (the standard GraphQL over HTTP
 *       contract).</li>
 *   <li>Deserialises the JSON request body to extract {@code query} and
 *       {@code variables}.</li>
 *   <li>Passes them to the {@link GraphQL} engine for execution.</li>
 *   <li>Serialises the {@link ExecutionResult} back to the GraphQL spec response
 *       format and writes it as the HTTP response body.</li>
 * </ol>
 *
 * <h2>GraphQL over HTTP — key differences from REST</h2>
 * <ul>
 *   <li><b>Single endpoint</b> — all operations go to {@code POST /graphql}, regardless
 *       of whether you are reading or writing.</li>
 *   <li><b>HTTP status is almost always 200</b> — even when a query fails, the HTTP
 *       layer returns 200 and the {@code errors} array in the response body carries the
 *       problem detail. HTTP 4xx/5xx are reserved for transport-level failures (bad JSON,
 *       missing {@code query} field, server crash).</li>
 *   <li><b>Clients request exactly what they need</b> — there is no over-fetching or
 *       under-fetching; the response shape mirrors the query shape.</li>
 * </ul>
 *
 * <h2>Port</h2>
 * Runs on {@value #PORT} to avoid conflicting with gRPC (8080) and REST (8081).
 */
public class GraphQlServer {

    private static final Logger log = LoggerFactory.getLogger(GraphQlServer.class);
    static final int PORT = 8082;

    public static void main(String[] args) {

        // --- Wiring ------------------------------------------------------------------
        EmployeeStore store  = new EmployeeStore();
        GraphQL       graphQL = new SchemaBuilder(store).build();

        // --- HTTP server -------------------------------------------------------------
        Javalin app = Javalin.create(config ->
                config.requestLogger.http((ctx, ms) ->
                        log.info("{} {} -> {} ({}ms)",
                                ctx.method(), ctx.path(), ctx.status(), ms.intValue())));

        // --- GraphQL endpoint --------------------------------------------------------
        // The GraphQL over HTTP spec mandates:
        //   Request:  POST /graphql  Content-Type: application/json
        //             Body: { "query": "...", "variables": { ... } }
        //   Response: Content-Type: application/json
        //             Body: { "data": { ... }, "errors": [ ... ] }
        app.post("/graphql", ctx -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String query = (String) body.get("query");
            if (query == null || query.isBlank()) {
                // Transport-level error: the required 'query' field is absent.
                ctx.status(400).json(Map.of("error", "The 'query' field is required"));
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> variables = (body.get("variables") instanceof Map<?, ?> m)
                    ? (Map<String, Object>) m
                    : Map.of();

            ExecutionInput input = ExecutionInput.newExecutionInput()
                    .query(query)
                    .variables(variables)
                    .build();

            // Execute the query. graphql-java catches all data fetcher exceptions and
            // adds them to the 'errors' array — this call never throws.
            ExecutionResult result = graphQL.execute(input);

            // toSpecification() serialises the result to the GraphQL spec format:
            // { "data": { ... } }  on success
            // { "data": null, "errors": [ { "message": "...", "locations": [...] } ] }  on error
            ctx.json(result.toSpecification());
        });

        // --- Utility routes ----------------------------------------------------------
        app.get("/health", ctx -> ctx.result("OK"));

        // --- Exception handler -------------------------------------------------------
        // Only fires for transport-level failures (e.g. malformed JSON body).
        // GraphQL execution errors are handled by graphql-java and surface in the
        // 'errors' array — they never reach here.
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception on {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).json(Map.of("error", "Internal server error"));
        });

        // --- Start + shutdown --------------------------------------------------------
        app.start(PORT);
        log.info("GraphQL API listening on http://localhost:{}/graphql", PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — stopping server");
            app.stop();
        }));
    }
}

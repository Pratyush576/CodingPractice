package org.pk.practices.design.api.rest.handler;

import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import org.pk.practices.design.api.rest.model.Employee;
import org.pk.practices.design.api.rest.model.EmployeeRequest;
import org.pk.practices.design.api.rest.store.EmployeeStore;

/**
 * Handles all HTTP requests for the {@code /api/employees} resource.
 *
 * <p>Each public method maps 1-to-1 to a route registered in {@code RestApiServer}
 * via a method reference (e.g. {@code app.get("/api/employees", handler::getAll)}).
 * Javalin calls these methods with a fully populated {@link Context} that provides
 * access to the request (path params, query params, body) and the response (status,
 * headers, body).
 *
 * <h2>Error handling strategy</h2>
 * Handlers throw typed Javalin HTTP exceptions rather than setting status codes
 * manually. This keeps handler methods readable — the happy path is the only path
 * visible — and the global exception handler in {@code RestApiServer} converts these
 * into a consistent JSON {@code ErrorResponse} body.
 *
 * <ul>
 *   <li>{@link NotFoundResponse} → 404 Not Found</li>
 *   <li>{@link BadRequestResponse} → 400 Bad Request</li>
 * </ul>
 *
 * <h2>Separation of concerns</h2>
 * Handlers contain only HTTP-layer logic: parse input, delegate to the store, map the
 * result to an HTTP response. Business logic (if any) belongs in a service layer
 * between the handler and the store.
 */
public class EmployeeHandler {

    private final EmployeeStore store;

    public EmployeeHandler(EmployeeStore store) {
        this.store = store;
    }

    /**
     * {@code GET /api/employees[?department=X]}
     *
     * <p>Returns all employees as a JSON array. Accepts an optional {@code department}
     * query parameter to filter the results, demonstrating how query params are used for
     * filtering/searching without changing the resource URL.
     *
     * <p>Always returns 200 — even an empty list is a valid, successful response.
     *
     * @param ctx the Javalin request/response context
     */
    public void getAll(Context ctx) {
        String department = ctx.queryParam("department"); // null if not provided
        ctx.json(store.findAll(department));
    }

    /**
     * {@code GET /api/employees/{id}}
     *
     * <p>Returns a single employee by their ID. Path parameters ({@code {id}} in the
     * route template) are accessed via {@link Context#pathParam(String)}.
     *
     * <p>Throws {@link NotFoundResponse} if no employee with that ID exists, which
     * Javalin's global exception handler maps to a 404 JSON response.
     *
     * @param ctx the Javalin request/response context
     */
    public void getById(Context ctx) {
        String id = ctx.pathParam("id");
        Employee employee = store.findById(id)
                .orElseThrow(() -> new NotFoundResponse("Employee not found: " + id));
        ctx.json(employee);
    }

    /**
     * {@code POST /api/employees}
     *
     * <p>Creates a new employee. The request body is deserialized into an
     * {@link EmployeeRequest} (no ID — the server assigns one). After validation the
     * employee is persisted and returned with its server-assigned ID.
     *
     * <p>Returns 201 Created rather than 200 OK to signal that a new resource was
     * created. The response body contains the full {@link Employee} including the new ID,
     * so the client does not need to make a follow-up GET request.
     *
     * @param ctx the Javalin request/response context
     */
    public void create(Context ctx) {
        EmployeeRequest req = ctx.bodyAsClass(EmployeeRequest.class);
        validate(req);
        Employee created = store.save(new Employee(store.nextId(), req.name(), req.department(), req.salary()));
        ctx.status(201).json(created);
    }

    /**
     * {@code PUT /api/employees/{id}}
     *
     * <p>Replaces an existing employee's data. PUT is semantically a full replacement —
     * the client sends the complete resource state, not just changed fields (that would
     * be PATCH). The ID always comes from the URL, not the request body.
     *
     * <p>Returns 404 if the employee does not already exist. Some APIs use PUT to create
     * resources at client-chosen IDs, but this implementation treats creation as POST-only
     * to keep ID generation server-side.
     *
     * @param ctx the Javalin request/response context
     */
    public void update(Context ctx) {
        String id = ctx.pathParam("id");
        if (store.findById(id).isEmpty()) {
            throw new NotFoundResponse("Employee not found: " + id);
        }
        EmployeeRequest req = ctx.bodyAsClass(EmployeeRequest.class);
        validate(req);
        Employee updated = store.save(new Employee(id, req.name(), req.department(), req.salary()));
        ctx.json(updated);
    }

    /**
     * {@code DELETE /api/employees/{id}}
     *
     * <p>Removes an employee from the store. Returns 204 No Content on success — there
     * is no meaningful body to return after a deletion. Returns 404 if the ID does not
     * exist, which makes the operation non-idempotent by design so the client knows
     * whether the resource was actually present.
     *
     * @param ctx the Javalin request/response context
     */
    public void delete(Context ctx) {
        String id = ctx.pathParam("id");
        if (!store.delete(id)) {
            throw new NotFoundResponse("Employee not found: " + id);
        }
        ctx.status(204);
    }

    /**
     * Validates the fields of a create/update request body.
     *
     * <p>Validation happens in the handler rather than in the record constructor so that
     * Jackson can deserialize the JSON into the record first (even if fields are blank),
     * and we can produce a specific, descriptive error message for each violation.
     *
     * @param req the deserialized request body
     * @throws BadRequestResponse (400) if any field fails validation
     */
    private void validate(EmployeeRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BadRequestResponse("name is required");
        }
        if (req.department() == null || req.department().isBlank()) {
            throw new BadRequestResponse("department is required");
        }
        if (req.salary() < 0) {
            throw new BadRequestResponse("salary must be non-negative");
        }
    }
}

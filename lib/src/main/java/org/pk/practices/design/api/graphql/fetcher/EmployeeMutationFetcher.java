package org.pk.practices.design.api.graphql.fetcher;

import graphql.schema.DataFetchingEnvironment;
import org.pk.practices.design.api.graphql.model.Employee;
import org.pk.practices.design.api.graphql.store.EmployeeStore;

import java.util.Map;

/**
 * Data fetchers for the GraphQL {@code Mutation} type.
 *
 * <h2>Mutations vs queries</h2>
 * Mutations are write operations (create, update, delete). The key difference from
 * queries is that graphql-java executes mutation fields <em>serially</em> — if a
 * client sends multiple mutations in one request they are guaranteed to run in order.
 * Query fields may execute concurrently (with async data fetchers).
 *
 * <h2>Input types</h2>
 * Mutation arguments that group multiple fields use GraphQL {@code input} types
 * (e.g. {@code CreateEmployeeInput}). graphql-java deserializes these into a plain
 * {@code Map<String, Object>} by default. Values in the map are already coerced to
 * Java types matching the schema: {@code String} for {@code String!},
 * {@code Integer} or {@code Double} for {@code Float}, {@code null} for absent
 * optional fields.
 *
 * <h2>Error handling</h2>
 * Any exception thrown from a data fetcher is caught by graphql-java and added to
 * the {@code errors} array of the response. The HTTP status remains 200 — the
 * GraphQL response envelope carries error information regardless of HTTP status.
 * Validation errors thrown here surface as structured errors in the response body.
 */
public class EmployeeMutationFetcher {

    private final EmployeeStore store;

    public EmployeeMutationFetcher(EmployeeStore store) {
        this.store = store;
    }

    /**
     * Resolves {@code Mutation.createEmployee(input: CreateEmployeeInput!)}.
     *
     * <p>The {@code input} argument arrives as a {@code Map<String, Object>}. All
     * fields are declared non-null in the schema, so graphql-java rejects the request
     * at the validation layer if any are missing — the {@code null} checks below are
     * a defensive second line of validation for blank strings and negative salaries.
     *
     * @param env carries the {@code input} argument
     * @return the newly created employee with its server-assigned ID
     * @throws IllegalArgumentException if any field fails business validation
     */
    public Employee createEmployee(DataFetchingEnvironment env) {
        Map<String, Object> input = env.getArgument("input");

        String name       = (String) input.get("name");
        String department = (String) input.get("department");
        double salary     = toDouble(input.get("salary"));

        validate(name, department, salary);

        return store.save(new Employee(store.nextId(), name, department, salary));
    }

    /**
     * Resolves {@code Mutation.updateEmployee(id: ID!, input: UpdateEmployeeInput!)}.
     *
     * <p>{@code UpdateEmployeeInput} uses all-optional fields (no {@code !}), enabling
     * partial updates (PATCH semantics): the client only sends the fields they want to
     * change. Fields absent from the input map retain the existing employee's values.
     *
     * <p>Returns {@code null} (rather than throwing) when the ID is not found, because
     * the schema declares the return type as nullable {@code Employee}. The client can
     * check whether the response {@code data.updateEmployee} is {@code null} to detect
     * a missing resource.
     *
     * @param env carries the {@code id} path argument and {@code input} map
     * @return the updated employee, or {@code null} if the ID does not exist
     */
    public Employee updateEmployee(DataFetchingEnvironment env) {
        String id = env.getArgument("id");
        Map<String, Object> input = env.getArgument("input");

        return store.findById(id).map(existing -> {
            String name       = input.containsKey("name")       ? (String) input.get("name")       : existing.name();
            String department = input.containsKey("department") ? (String) input.get("department") : existing.department();
            double salary     = (input.containsKey("salary") && input.get("salary") != null)
                                ? toDouble(input.get("salary")) : existing.salary();
            return store.save(new Employee(id, name, department, salary));
        }).orElse(null);
    }

    /**
     * Resolves {@code Mutation.deleteEmployee(id: ID!)}.
     *
     * <p>Returns a {@code Boolean} rather than the deleted object — a common GraphQL
     * pattern when there is nothing useful to return after deletion. The client receives
     * {@code true} if the employee existed and was removed, {@code false} otherwise.
     *
     * @param env carries the {@code id} argument
     * @return {@code true} if an employee with that ID was found and deleted
     */
    public boolean deleteEmployee(DataFetchingEnvironment env) {
        String id = env.getArgument("id");
        return store.delete(id);
    }

    /**
     * Converts a graphql-java numeric argument to {@code double}.
     *
     * <p>GraphQL {@code Float} values arrive as either {@code Integer} or
     * {@code Double} depending on whether the literal has a decimal point.
     * Casting via {@link Number} handles both cases uniformly.
     */
    private double toDouble(Object value) {
        return ((Number) value).doubleValue();
    }

    /** Validates business rules that the GraphQL type system cannot express. */
    private void validate(String name, String department, double salary) {
        if (name == null || name.isBlank())            throw new IllegalArgumentException("name is required");
        if (department == null || department.isBlank()) throw new IllegalArgumentException("department is required");
        if (salary < 0)                                 throw new IllegalArgumentException("salary must be non-negative");
    }
}

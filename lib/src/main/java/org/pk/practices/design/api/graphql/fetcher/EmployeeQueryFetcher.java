package org.pk.practices.design.api.graphql.fetcher;

import graphql.schema.DataFetchingEnvironment;
import org.pk.practices.design.api.graphql.model.Employee;
import org.pk.practices.design.api.graphql.store.EmployeeStore;

import java.util.List;

/**
 * Data fetchers for the GraphQL {@code Query} type.
 *
 * <h2>What is a data fetcher?</h2>
 * In graphql-java, every field on every type can have a <em>data fetcher</em> — a
 * function that supplies the value for that field during query execution. If no
 * fetcher is registered, graphql-java falls back to {@code PropertyDataFetcher},
 * which resolves the field by calling a matching method on the parent object
 * (e.g. {@code employee.name()} for the {@code name} field).
 *
 * <p>Top-level {@code Query} fields always need explicit fetchers because there is
 * no parent object — the fetcher <em>is</em> the entry point into your data.
 *
 * <h2>Method signatures</h2>
 * Each method here matches the {@code DataFetcher<T>} functional interface:
 * {@code T get(DataFetchingEnvironment env) throws Exception}. This lets them be
 * registered as method references in {@code SchemaBuilder}:
 * <pre>
 *   .dataFetcher("employees", queryFetcher::employees)
 * </pre>
 *
 * <h2>Execution model</h2>
 * graphql-java calls fetchers on the thread that executes the query (by default, the
 * HTTP request thread). The store uses {@link java.util.concurrent.ConcurrentHashMap}
 * so concurrent fetchers do not block each other.
 */
public class EmployeeQueryFetcher {

    private final EmployeeStore store;

    public EmployeeQueryFetcher(EmployeeStore store) {
        this.store = store;
    }

    /**
     * Resolves {@code Query.employees}: returns every employee in the store.
     *
     * <p>The returned list is declared {@code [Employee!]!} in the schema — a
     * non-null list of non-null employees. Returning an empty list is valid;
     * returning {@code null} would violate the schema contract and cause a runtime error.
     *
     * @param env the execution environment (unused — no arguments on this field)
     * @return all employees sorted by ID
     */
    public List<Employee> employees(DataFetchingEnvironment env) {
        return store.findAll(null);
    }

    /**
     * Resolves {@code Query.employee(id: ID!)}: looks up one employee by ID.
     *
     * <p>The schema declares the return type as {@code Employee} (nullable), so
     * returning {@code null} is legal and signals "not found" to the client — no
     * exception required. This is the GraphQL idiom for optional resources;
     * contrast with REST where 404 is used instead.
     *
     * <p>{@link DataFetchingEnvironment#getArgument(String)} returns the argument
     * value already coerced to the appropriate Java type (here, {@code String} for
     * {@code ID}).
     *
     * @param env carries the {@code id} argument from the query
     * @return the matching employee, or {@code null} if not found
     */
    public Employee employee(DataFetchingEnvironment env) {
        String id = env.getArgument("id");
        return store.findById(id).orElse(null);
    }

    /**
     * Resolves {@code Query.employeesByDepartment(department: String!)}: filters
     * employees by department name (case-insensitive).
     *
     * <p>This field shows how arguments narrow the result set without changing the
     * URL — there is only one endpoint {@code /graphql}. In REST you would use a
     * query parameter ({@code ?department=Engineering}) on a separate GET endpoint.
     *
     * @param env carries the {@code department} argument from the query
     * @return employees in the given department, sorted by ID
     */
    public List<Employee> employeesByDepartment(DataFetchingEnvironment env) {
        String department = env.getArgument("department");
        return store.findAll(department);
    }
}

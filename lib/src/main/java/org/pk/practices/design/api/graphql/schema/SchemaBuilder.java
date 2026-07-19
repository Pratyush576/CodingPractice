package org.pk.practices.design.api.graphql.schema;

import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.pk.practices.design.api.graphql.fetcher.EmployeeMutationFetcher;
import org.pk.practices.design.api.graphql.fetcher.EmployeeQueryFetcher;
import org.pk.practices.design.api.graphql.store.EmployeeStore;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static graphql.schema.idl.RuntimeWiring.newRuntimeWiring;

/**
 * Builds the executable {@link GraphQL} instance from the SDL schema file and
 * the application's data fetchers.
 *
 * <p>This class is the assembly point of the GraphQL layer. It separates the three
 * distinct concerns involved in standing up a graphql-java schema:
 *
 * <ol>
 *   <li><b>Parsing</b> — {@link SchemaParser} reads the SDL ({@code .graphqls}) file
 *       and produces a {@link TypeDefinitionRegistry}: an in-memory representation of
 *       every type, field, and directive in the schema. At this stage the schema is
 *       purely structural — no code is attached yet.</li>
 *
 *   <li><b>Wiring</b> — {@link RuntimeWiring} maps each field on each type to a
 *       data fetcher. This is where the SDL type system meets your Java code. Fields
 *       without an explicit fetcher fall back to {@code PropertyDataFetcher}, which
 *       resolves values reflectively from the parent object (calling e.g.
 *       {@code employee.name()} for the {@code name} field).</li>
 *
 *   <li><b>Code generation</b> — {@link SchemaGenerator#makeExecutableSchema}
 *       merges the registry and the wiring into an executable {@link GraphQLSchema},
 *       which is then wrapped in a {@link GraphQL} instance ready to execute queries.</li>
 * </ol>
 *
 * <h2>Schema file location</h2>
 * The SDL file is loaded from the classpath ({@code /graphql/schema.graphqls}), which
 * corresponds to {@code src/main/resources/graphql/schema.graphqls} in the project.
 * Loading from the classpath instead of a filesystem path ensures the schema is
 * packaged inside the JAR and works identically in every environment.
 */
public class SchemaBuilder {

    private final EmployeeQueryFetcher queryFetcher;
    private final EmployeeMutationFetcher mutationFetcher;

    public SchemaBuilder(EmployeeStore store) {
        this.queryFetcher    = new EmployeeQueryFetcher(store);
        this.mutationFetcher = new EmployeeMutationFetcher(store);
    }

    /**
     * Parses the SDL schema file, wires data fetchers, and returns an executable
     * {@link GraphQL} instance.
     *
     * <p>This is called once at server startup. The resulting {@link GraphQL} object
     * is thread-safe and should be shared across all request threads.
     *
     * @return a fully wired, ready-to-execute GraphQL engine
     * @throws NullPointerException if {@code schema.graphqls} is not found on the classpath
     */
    public GraphQL build() {
        // Step 1 — parse: SDL text → structural type registry
        TypeDefinitionRegistry registry = new SchemaParser().parse(
                new InputStreamReader(
                        Objects.requireNonNull(
                                getClass().getResourceAsStream("/graphql/schema.graphqls"),
                                "/graphql/schema.graphqls not found on classpath"),
                        StandardCharsets.UTF_8));

        // Step 2 — wire: attach data fetchers to each field that needs one.
        // Only top-level Query/Mutation fields need explicit fetchers.
        // Sub-fields of Employee resolve automatically via PropertyDataFetcher
        // (record accessor methods: id(), name(), department(), salary()).
        RuntimeWiring wiring = newRuntimeWiring()
                .type("Query", builder -> builder
                        .dataFetcher("employees",             queryFetcher::employees)
                        .dataFetcher("employee",              queryFetcher::employee)
                        .dataFetcher("employeesByDepartment", queryFetcher::employeesByDepartment))
                .type("Mutation", builder -> builder
                        .dataFetcher("createEmployee", mutationFetcher::createEmployee)
                        .dataFetcher("updateEmployee", mutationFetcher::updateEmployee)
                        .dataFetcher("deleteEmployee", mutationFetcher::deleteEmployee))
                .build();

        // Step 3 — generate: merge registry + wiring into an executable schema
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, wiring);
        return GraphQL.newGraphQL(schema).build();
    }
}

package org.pk.practices.design.api.graphql.model;

/**
 * Domain model for a single employee.
 *
 * <p>Modelled as an immutable Java record for the same reasons as the REST package:
 * no accidental mutation in a multi-threaded server and zero boilerplate.
 *
 * <h2>How graphql-java resolves fields on this object</h2>
 * When a data fetcher returns an {@code Employee}, graphql-java resolves each requested
 * sub-field (e.g. {@code name}, {@code salary}) using its default
 * {@code PropertyDataFetcher}. For each field it tries, in order:
 * <ol>
 *   <li>A method named {@code getName()} — JavaBean convention.</li>
 *   <li>A method named {@code name()} — Java record accessor convention.</li>
 *   <li>A public field named {@code name}.</li>
 * </ol>
 * Records expose accessors via {@code name()}, so graphql-java resolves all fields
 * automatically without any annotation or configuration.
 *
 * @param id         server-assigned unique identifier
 * @param name       full name of the employee
 * @param department team or business unit
 * @param salary     annual salary (maps to GraphQL {@code Float})
 */
public record Employee(String id, String name, String department, double salary) {}

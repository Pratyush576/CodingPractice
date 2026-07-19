package org.pk.practices.design.api.rest.model;

/**
 * Domain model representing a single employee resource.
 *
 * <p>Modelled as a Java record so instances are immutable by construction — every
 * "mutation" (create, update) produces a new record. This removes a whole class of
 * accidental shared-state bugs in a multi-threaded server.
 *
 * <p>Jackson 2.12+ can serialize and deserialize records without extra annotations,
 * provided the project is compiled with the {@code -parameters} javac flag (see
 * {@code build.gradle.kts}), which embeds constructor parameter names in the bytecode
 * so Jackson can match them to JSON fields.
 *
 * <p>JSON representation:
 * <pre>
 * {
 *   "id":         "1",
 *   "name":       "Alice",
 *   "department": "Engineering",
 *   "salary":     95000.0
 * }
 * </pre>
 *
 * @param id         server-assigned unique identifier (never set by the client)
 * @param name       full name of the employee
 * @param department team or business unit the employee belongs to
 * @param salary     annual salary in the default currency
 */
public record Employee(String id, String name, String department, double salary) {}

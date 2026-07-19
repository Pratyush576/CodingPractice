package org.pk.practices.design.api.rest.model;

/**
 * Request body for create ({@code POST}) and update ({@code PUT}) operations.
 *
 * <p>This is a separate type from {@link Employee} because clients never supply the
 * {@code id} — the server controls identity. Using a distinct DTO makes that contract
 * explicit in the type system rather than relying on convention or documentation.
 *
 * <p>Expected JSON body:
 * <pre>
 * {
 *   "name":       "Dave",
 *   "department": "Finance",
 *   "salary":     80000.0
 * }
 * </pre>
 *
 * <p>Validation is performed in {@code EmployeeHandler} before any store interaction,
 * keeping this class a pure data carrier with no side effects.
 *
 * @param name       full name of the employee (required, non-blank)
 * @param department team or business unit (required, non-blank)
 * @param salary     annual salary — must be ≥ 0
 */
public record EmployeeRequest(String name, String department, double salary) {}
